package ru.nokia.lcbin;

import ru.nokia.lcbin.io.InputScanner;
import ru.nokia.lcbin.io.LcbinRecordReader;
import ru.nokia.lcbin.io.SnapshotFilter;
import ru.nokia.lcbin.io.SourceFile;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.output.CsvWriter;
import ru.nokia.lcbin.output.UeSummary;
import ru.nokia.lcbin.parsers.ParseContext;
import ru.nokia.lcbin.parsers.ParserRegistry;
import ru.nokia.lcbin.parsers.RecordDispatcher;
import ru.nokia.lcbin.parsers.RecordParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Точка входа: {@code java -jar lcbin-parser.jar <входная папка или файл> <выходная папка> [опции]}
 * <pre>
 *   --password <pw>      пароль для зашифрованных 7z / zip
 *   --threads <n>        рабочих потоков (по умолчанию число ядер)
 *   --snapshots all|largest|median   какие снимки lcbin_<байт> одного потока разбирать (по умолчанию all)
 *   --blocks a,b,c       какие блоки запускать: file-header,heartbeat,session,ue-events,status (по умолчанию все)
 *   --raw                писать hex декодированных RRC-тел
 *   --sep ;              разделитель CSV (по умолчанию запятая)
 *   --no-summary         не писать сводку по UE
 * </pre>
 * Выход: {@code <имя>.csv} на каждый входной поток и {@code <имя>.ue_ta.csv} (медиана TA по UE без нулей).
 * Коды выхода: 0 — успех, 1 — хотя бы один вход не разобран, 2 — ошибка в аргументах.
 *
 * <p>Как устроен конвейер: {@code InputScanner} находит потоки (распаковывая архивы) → {@code SnapshotFilter}
 * отбирает снимки → для каждого потока в своём потоке пула: {@code LcbinRecordReader} режет байты на записи →
 * {@code RecordDispatcher} раздаёт их блокам (второй пул) → {@code CsvWriter} пишет результат.
 */
public final class Main {
    public static final String USAGE = "usage: lcbin-parser <inputDirOrFile> <outputDir> [--password pw] [--threads n] "
            + "[--snapshots all|largest|median] [--blocks list] [--raw] [--sep c] [--no-summary]";

    /** Разобранная командная строка. */
    public record Options(Path input, Path output, char[] password, int threads, SnapshotFilter.Strategy snapshots,
                          ParserRegistry registry, boolean raw, char separator, boolean summary) {

        public static Options parse(String[] args) {
            if (args.length < 2) throw new IllegalArgumentException(USAGE);
            Path in = Path.of(args[0]);
            Path out = Path.of(args[1]);
            char[] password = null;
            int threads = Runtime.getRuntime().availableProcessors();
            SnapshotFilter.Strategy strategy = SnapshotFilter.Strategy.ALL;
            ParserRegistry registry = ParserRegistry.full();
            boolean raw = false;
            boolean summary = true;
            char sep = ',';
            for (int i = 2; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--password" -> password = value(a, args, ++i).toCharArray();
                    case "--threads" -> threads = Integer.parseInt(value(a, args, ++i));
                    case "--snapshots" -> strategy = SnapshotFilter.Strategy.valueOf(value(a, args, ++i).toUpperCase(Locale.ROOT));
                    case "--blocks" -> registry = selectBlocks(value(a, args, ++i));
                    case "--raw" -> raw = true;
                    case "--sep" -> {
                        String v = value(a, args, ++i);
                        if (v.length() != 1) throw new IllegalArgumentException("--sep needs exactly one character");
                        sep = v.charAt(0);
                    }
                    case "--no-summary" -> summary = false;
                    default -> throw new IllegalArgumentException("unknown option " + a + "\n" + USAGE);
                }
            }
            if (threads < 1) throw new IllegalArgumentException("--threads must be >= 1");
            return new Options(in, out, password, threads, strategy, registry, raw, sep, summary);
        }

        private static String value(String opt, String[] args, int i) {
            if (i >= args.length) throw new IllegalArgumentException(opt + " requires a value\n" + USAGE);
            return args[i];
        }

        private static ParserRegistry selectBlocks(String list) {
            List<RecordParser> chosen = new ArrayList<>();
            for (String n : list.split(",")) {
                RecordParser block = ParserRegistry.full().blocks().stream()
                        .filter(b -> b.name().equalsIgnoreCase(n.trim())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown block '" + n + "', known: "
                                + ParserRegistry.full().blocks().stream().map(RecordParser::name).toList()));
                chosen.add(block);
            }
            return ParserRegistry.of(chosen.toArray(new RecordParser[0]));
        }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(Options.parse(args), System.out, System.err);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            code = 2;
        } catch (Exception e) {
            System.err.println("fatal: " + e);
            e.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    /** Выполняет всю работу; не вызывает System.exit, чтобы можно было запускать из тестов. */
    public static int run(Options o, PrintStream out, PrintStream err) throws IOException, InterruptedException {
        Files.createDirectories(o.output());
        InputScanner scanner = new InputScanner(o.password());
        List<SourceFile> sources = uniqueStems(SnapshotFilter.apply(scanner.scan(o.input()), o.snapshots()));
        out.printf("%d input stream(s), %d thread(s), blocks=%s%n", sources.size(), o.threads(),
                o.registry().blocks().stream().map(RecordParser::name).toList());
        if (sources.isEmpty()) {
            err.println("no input streams found in " + o.input());
            return 1;
        }
        // Два отдельных пула: задачи «по файлу» ждут задачи «по блокам». В одном ограниченном пуле это deadlock.
        ExecutorService filePool = Executors.newFixedThreadPool(o.threads());
        ExecutorService blockPool = Executors.newFixedThreadPool(o.threads());
        int failed = scanner.errors().size();
        try {
            RecordDispatcher dispatcher = new RecordDispatcher(o.registry(), blockPool, 2000);
            CsvWriter csv = new CsvWriter(o.separator());
            List<Future<String>> jobs = new ArrayList<>();
            for (SourceFile src : sources) {
                jobs.add(filePool.submit(() -> processOne(src, o.output(), dispatcher, csv, o.raw(), o.summary())));
            }
            for (Future<String> j : jobs) {
                try {
                    out.println(j.get());
                } catch (ExecutionException e) {
                    failed++;
                    err.println("FAILED: " + e.getCause());
                    e.getCause().printStackTrace(err);
                }
            }
        } finally {
            filePool.shutdownNow();
            blockPool.shutdownNow();
            filePool.awaitTermination(10, TimeUnit.SECONDS);
            blockPool.awaitTermination(10, TimeUnit.SECONDS);
        }
        return failed > 0 ? 1 : 0;
    }

    /**
     * Два разных источника могут дать одинаковую основу имени (например {@code a/b.lcbin} и {@code a_b.lcbin}),
     * а на Windows ещё и {@code A.lcbin} = {@code a.lcbin}. Писать в один файл из двух потоков нельзя,
     * поэтому повторам добавляем суффикс ~2, ~3, ...
     */
    static List<SourceFile> uniqueStems(List<SourceFile> sources) {
        Map<String, Integer> seen = new HashMap<>();
        List<SourceFile> unique = new ArrayList<>(sources.size());
        for (SourceFile s : sources) {
            int n = seen.merge(s.outputStem().toLowerCase(Locale.ROOT), 1, Integer::sum);
            unique.add(n == 1 ? s : new SourceFile(s.name(), s.outputStem() + "~" + n, s.data()));
        }
        return unique;
    }

    static String processOne(SourceFile src, Path outDir, RecordDispatcher dispatcher, CsvWriter csv,
                             boolean raw, boolean summary) throws IOException, InterruptedException {
        List<LcbinRecord> records = new LcbinRecordReader().readAll(src.data());
        ParseContext ctx = new ParseContext(src.name(), ParseContext.TA_METRES, raw);
        List<Row> rows = dispatcher.parseAll(records, ctx);
        Path target = outDir.resolve(src.outputStem() + ".csv");
        csv.write(target, rows);
        long ta = rows.stream().filter(r -> r.get(Columns.TA) != null).count();
        long geo = rows.stream().filter(r -> r.get(Columns.LAT) != null).count();
        long warn = rows.stream().filter(r -> r.get(Columns.WARNING) != null).count();
        if (summary) {
            List<Row> s = new UeSummary().summarize(rows, ctx.taMetres());
            csv.write(outDir.resolve(src.outputStem() + ".ue_ta.csv"), UeSummary.COLUMNS, s);
        }
        return String.format("%s: %d records -> %d rows (%d TA, %d geo, %d warnings) -> %s",
                src.name(), records.size(), rows.size(), ta, geo, warn, target.getFileName());
    }
}
