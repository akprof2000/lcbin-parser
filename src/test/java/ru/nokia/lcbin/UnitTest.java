package ru.nokia.lcbin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class UnitTest {
    @TempDir Path tmp;

    @Test
    void csvQuotingAndSchema() throws Exception {
        CsvWriter w = new CsvWriter(',');
        Row r = new Row("X");
        r.put("a", "plain").put("b", "has,comma").put("c", "has \"quote\"").put("d", "line\nbreak").put("e", 5).put("f", null);
        Path f = tmp.resolve("t.csv");
        w.write(f, List.of("a", "b", "c", "d", "e", "f"), List.of(r));
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals("a,b,c,d,e,f", lines.get(0));
        assertEquals("plain,\"has,comma\",\"has \"\"quote\"\"\",\"line", lines.get(1));
        assertEquals("break\",5,", lines.get(2));
        CsvWriter semi = new CsvWriter(';');
        semi.write(f, List.of("a", "b"), List.of(r));
        assertEquals("plain;has,comma", Files.readAllLines(f).get(1));
    }

    @Test
    void snapshotFilterStrategies() {
        List<SourceFile> files = new ArrayList<>();
        for (int n : new int[]{300, 100, 0, 200}) files.add(new SourceFile("d/neA.lcbin_" + n, "neA_" + n, new byte[n]));
        files.add(new SourceFile("d/neB.lcbin_50", "neB", new byte[50]));
        files.add(new SourceFile("other.bin", "other", new byte[10]));
        assertEquals(6, SnapshotFilter.apply(files, SnapshotFilter.Strategy.ALL).size());
        List<SourceFile> largest = SnapshotFilter.apply(files, SnapshotFilter.Strategy.LARGEST);
        assertEquals(List.of("d/neA.lcbin_300", "d/neB.lcbin_50", "other.bin"), largest.stream().map(SourceFile::name).toList());
        List<SourceFile> median = SnapshotFilter.apply(files, SnapshotFilter.Strategy.MEDIAN);
        assertEquals("d/neA.lcbin_200", median.get(0).name(), "median of {100,200,300} (zero-size dropped)");
    }

    @Test
    void outputStemIsSafeAndDistinct() {
        assertEquals("a.7z_dir_ne1_%3A%3Affff%3A10.0.0.1_15011.lcbin_5", SourceFile.stemOf("a.7z/dir/ne1_%3A%3Affff%3A10.0.0.1_15011.lcbin_5"));
        assertEquals("x_y.lcbin_1", SourceFile.stemOf("x\\y.lcbin_1"));
        assertNotEquals(SourceFile.stemOf("a.zip/f"), SourceFile.stemOf("b.zip/f"));
        assertTrue(SourceFile.stemOf("p".repeat(400)).length() <= 180);
        assertFalse(SourceFile.stemOf("c:/tmp/../x").contains(".."), "path traversal neutralised: " + SourceFile.stemOf("c:/tmp/../x"));
    }

    @Test
    void ueSummaryMedianDropsZeros() {
        List<Row> rows = new ArrayList<>();
        LcbinRecord rec = new LcbinRecord(0, 0, 0, 0x48, new byte[]{0x48});
        long[] tas = {0, 4, 6, 5, 0, 40};
        int i = 0;
        for (long ta : tas) {
            Row r = new Row(rec, i++, "UE_TA");
            r.put(Columns.ENB_ID, 1).put(Columns.CELL_ID, 2).put(Columns.UE_ID, "aa").put(Columns.C_RNTI, 7)
             .put(Columns.TIME_UTC, "2025-01-01 00:00:0" + i).put(Columns.TA, ta);
            rows.add(r);
        }
        Row meas = new Row(rec, 9, "UE_RRC").put(Columns.ENB_ID, 1).put(Columns.CELL_ID, 2).put(Columns.UE_ID, "aa")
                .put(Columns.C_RNTI, 7).put(Columns.TIME_UTC, "t").put(Columns.RSRP_DBM, -100.0);
        rows.add(meas);
        List<Row> s = new UeSummary().summarize(rows, 78.125);
        assertEquals(1, s.size());
        assertEquals(6, s.get(0).get("samples"));
        assertEquals(4, s.get(0).get("samples_nonzero"));
        assertEquals(5.5, s.get(0).get("ta_median"));
        assertEquals(430L, s.get(0).get("distance_median_m"));
        assertEquals(4L, s.get(0).get("ta_min"));
        assertEquals(40L, s.get(0).get("ta_max"));
        assertEquals(-100.0, s.get(0).get("rsrp_median_dbm"));
    }

    @Test
    void dispatcherKeepsStreamOrderAcrossChunksAndBlocks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<LcbinRecord> recs = new ArrayList<>();
            String[] vs = {SampleStream.HEADER, SampleStream.UE_EVENTS, SampleStream.HEARTBEAT, SampleStream.STATUS_SHORT, SampleStream.SESSION_END};
            for (int i = 0; i < 500; i++) {
                byte[] p = SampleStream.hex(vs[i % vs.length]);
                recs.add(new LcbinRecord(i, i * 10L, p[0] == 0x18 ? LcbinRecord.MARKER_STATUS : LcbinRecord.MARKER_MAIN, p[0] & 0xFF, p));
            }
            List<Row> rows = new RecordDispatcher(ParserRegistry.full(), pool, 7).parseAll(recs, ParseContext.of("o"));
            assertEquals(500 - 100 + 100 * 4, rows.size());
            int prev = -1, prevSub = -1;
            for (Row r : rows) {
                assertTrue(r.recordIndex() > prev || (r.recordIndex() == prev && r.subIndex() > prevSub), "ordered");
                prev = r.recordIndex();
                prevSub = r.subIndex();
            }
            assertEquals(0L, rows.get(0).get(Columns.RECORD_OFFSET));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void registrySelection() {
        ParserRegistry only = ParserRegistry.of(new ru.nokia.lcbin.parsers.UeEventsParser());
        assertEquals("ue-events", only.resolve(0x48).name());
        assertEquals("unknown", only.resolve(0x60).name());
        assertEquals("unknown", ParserRegistry.full().resolve(0x99).name());
        assertEquals("session", ParserRegistry.full().resolve(0x2E).name());
        assertEquals("session", ParserRegistry.full().resolve(0x3C).name());
    }
}
