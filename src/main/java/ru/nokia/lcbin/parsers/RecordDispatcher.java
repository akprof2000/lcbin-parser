package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Раздаёт записи блокам и выполняет разбор параллельно.
 *
 * <p>Схема: записи группируются по блоку, каждая группа режется на чанки по {@code chunkSize} записей,
 * каждый чанк — отдельная задача в пуле. Так самый «тяжёлый» блок (0x48, событий UE в разы больше
 * остальных) тоже распараллеливается. После завершения строки сортируются по (индекс записи, индекс
 * элемента), поэтому CSV всегда в порядке исходного потока, как бы задачи ни перемешались.
 *
 * <p>Важно: пул, который сюда передают, не должен быть тем же пулом, в котором ждут результата
 * {@link #parseAll} — иначе при числе файлов ≥ числу потоков наступит взаимная блокировка (deadlock).
 */
public final class RecordDispatcher {
    private final ParserRegistry registry;
    private final ExecutorService executor;
    private final int chunkSize;

    public RecordDispatcher(ParserRegistry registry, ExecutorService executor, int chunkSize) {
        this.registry = registry;
        this.executor = executor;
        this.chunkSize = Math.max(1, chunkSize);
    }

    public List<Row> parseAll(List<LcbinRecord> records, ParseContext ctx) throws InterruptedException {
        Map<RecordParser, List<LcbinRecord>> byBlock = records.stream()
                .collect(Collectors.groupingBy(r -> registry.resolve(r.type())));

        List<Future<List<Row>>> tasks = new ArrayList<>();
        for (Map.Entry<RecordParser, List<LcbinRecord>> e : byBlock.entrySet()) {
            RecordParser block = e.getKey();
            List<LcbinRecord> recs = e.getValue();
            for (int i = 0; i < recs.size(); i += chunkSize) {
                List<LcbinRecord> chunk = recs.subList(i, Math.min(recs.size(), i + chunkSize));
                tasks.add(executor.submit(() -> parseChunk(block, chunk, ctx)));
            }
        }
        List<Row> rows = new ArrayList<>(records.size());
        for (Future<List<Row>> f : tasks) {
            try {
                rows.addAll(f.get());
            } catch (ExecutionException ex) {
                throw new IllegalStateException("parser block failed: " + ex.getCause(), ex.getCause());
            }
        }
        rows.sort(Comparator.comparingInt(Row::recordIndex).thenComparingInt(Row::subIndex));
        return rows;
    }

    private static List<Row> parseChunk(RecordParser block, List<LcbinRecord> chunk, ParseContext ctx) {
        List<Row> out = new ArrayList<>(chunk.size());
        for (LcbinRecord r : chunk) {
            try {
                out.addAll(block.parse(r, ctx));
            } catch (RuntimeException e) {
                // блок не должен ронять весь файл: сохраняем запись как строку с предупреждением
                out.add(new Row(r, 0, "PARSE_ERROR")
                        .put(Columns.WARNING, block.name() + ": " + e)
                        .put(Columns.RAW_HEX, Ids.hex(r.payload())));
            }
        }
        return out;
    }
}
