package ru.nokia.lcbin;

import ru.nokia.lcbin.io.LcbinRecordReader;
import ru.nokia.lcbin.io.SourceFile;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.output.CsvWriter;
import ru.nokia.lcbin.output.UeSummary;
import ru.nokia.lcbin.parsers.ParseContext;
import ru.nokia.lcbin.parsers.ParserRegistry;
import ru.nokia.lcbin.parsers.RecordDispatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Developer runner over already-extracted files (no archive dependencies needed). */
public final class DevRun {
    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]);
        Path out = Path.of(a[1]);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        RecordDispatcher d = new RecordDispatcher(ParserRegistry.full(), pool, 2000);
        CsvWriter csv = new CsvWriter(',');
        try (var s = Files.list(in)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                SourceFile src = new SourceFile(f.getFileName().toString(), SourceFile.stemOf(f.getFileName().toString()), Files.readAllBytes(f));
                long t0 = System.nanoTime();
                var recs = new LcbinRecordReader().readAll(src.data());
                List<Row> rows = d.parseAll(recs, ParseContext.of(src.name()));
                csv.write(out.resolve(src.outputStem() + ".csv"), rows);
                csv.write(out.resolve(src.outputStem() + ".ue_ta.csv"), UeSummary.COLUMNS, new UeSummary().summarize(rows, ParseContext.TA_METRES));
                long ta = rows.stream().filter(r -> r.get("timing_advance") != null).count();
                long warn = rows.stream().filter(r -> r.get("warning") != null).count();
                System.out.printf("%-50s recs=%6d rows=%6d ta=%5d warn=%d %.0f ms%n", src.name().substring(0, Math.min(50, src.name().length())),
                        recs.size(), rows.size(), ta, warn, (System.nanoTime() - t0) / 1e6);
            }
        }
        pool.shutdown();
    }
}
