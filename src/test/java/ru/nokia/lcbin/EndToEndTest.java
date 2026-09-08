package ru.nokia.lcbin;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nokia.lcbin.io.SnapshotFilter;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.parsers.ParserRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Весь конвейер через {@link Main#run}: папка, zip, 7z, вложенные архивы, много файлов при малом числе потоков. */
class EndToEndTest {
    private static final Duration LIMIT = Duration.ofSeconds(60);

    @TempDir Path tmp;

    private static byte[] zip(String entry, byte[] data) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(data);
            z.closeEntry();
        }
        return bo.toByteArray();
    }

    private static void sevenZ(Path target, String entry, byte[] data) throws IOException {
        try (SevenZOutputFile z = new SevenZOutputFile(target.toFile())) {
            SevenZArchiveEntry e = new SevenZArchiveEntry();
            e.setName(entry);
            e.setSize(data.length);
            z.putArchiveEntry(e);
            z.write(data);
            z.closeArchiveEntry();
        }
    }

    private int run(Path in, Path out, String... extra) throws Exception {
        String[] args = Stream.concat(Stream.of(in.toString(), out.toString()), Stream.of(extra)).toArray(String[]::new);
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int code = assertTimeoutPreemptively(LIMIT, () -> Main.run(Main.Options.parse(args), new PrintStream(so), new PrintStream(se)),
                "parser hung (deadlock?)");
        if (code != 0) fail("exit " + code + "\n" + so + "\n" + se);
        return code;
    }

    private static List<String> lines(Path csv) throws IOException {
        return Files.readAllLines(csv, StandardCharsets.UTF_8);
    }

    private static long count(List<String> l, String needle) {
        return l.stream().filter(s -> s.contains(needle)).count();
    }

    @Test
    void plainFolderProducesOneCsvPerFile() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path out = tmp.resolve("out");
        Files.write(in.resolve("ne1_x.lcbin_100"), SampleStream.build(3));
        Files.createDirectories(in.resolve("sub"));
        Files.write(in.resolve("sub").resolve("ne2_x.lcbin_100"), SampleStream.build(1));
        run(in, out, "--threads", "2");
        List<Path> csv;
        try (Stream<Path> s = Files.list(out)) { csv = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList(); }
        assertEquals(4, csv.size(), "2 streams x (events + ue_ta) -> " + csv);
        Path main = out.resolve("ne1_x.lcbin_100.csv");
        List<String> l = lines(main);
        assertEquals(String.join(",", Columns.ALL), l.get(0));
        assertEquals(1, count(l, ",FILE_HEADER,"));
        assertEquals(1, count(l, ",HEARTBEAT,"));
        assertEquals(1, count(l, "TRACE_SESSION_START"));
        assertEquals(1, count(l, "TRACE_SESSION_END"));
        assertEquals(3 * 2, count(l, ",UE_TA,"));
        assertEquals(3 * 2, count(l, ",UE_RRC,"));
        assertEquals(3, count(l, ",UE_SESSION+TA,"));
        assertEquals(1, count(l, ",STATUS,STATUS,"));
        assertEquals(1, count(l, ",UE_INFORMATION,"));
        assertTrue(count(l, ",MDT_LOG,") >= 1, "MDT entries from the long status record");
        assertTrue(l.stream().anyMatch(s -> s.contains(",UE_TA,") && s.contains(",19,1484,")), "TA 19 -> 1484 m");
        assertTrue(l.stream().anyMatch(s -> s.contains(",UE_SESSION+TA,") && s.contains(",100000,5000000,001-01,100,30,")), "session prefix decoded");
        assertTrue(l.stream().anyMatch(s -> s.contains(",UE_INFORMATION,") && s.contains(",2,true,")), "rach-Report decoded");
        assertEquals(1, count(l, ",RLF_REPORT,"));
        assertEquals(2, count(l, ",MDT_LOG,"));   // одна длинная запись 0x18 на поток, в ней 2 записи журнала
        assertTrue(l.stream().anyMatch(s -> s.contains(",MDT_LOG,") && s.contains("gsm68/dcs1800/ncc1bcc3:rssi-61dBm")), "MDT GSM neighbour");
        assertTrue(l.stream().anyMatch(s -> s.contains(",UE_RRC,") && s.contains("sf1:-95dBm/-12dB") && s.contains(",10.50") && s.contains(",-20.2")),
                "GNSS point + SCell in MeasurementReport: " + l.stream().filter(x -> x.contains("sf1:")).findFirst().orElse(""));
        assertTrue(l.stream().anyMatch(s -> s.contains("TRACE_SESSION_END") && s.contains(",3,")), "n_msgs on END");
        assertEquals(0, count(l, "PARSE_ERROR"));
        assertEquals(0, l.stream().skip(1).filter(s -> !s.endsWith(",")).count(), "no warnings expected");
        List<String> ue = lines(out.resolve("ne1_x.lcbin_100.ue_ta.csv"));
        assertEquals("enb_id,cell_id,ue_id,c_rnti,enb_ue_s1ap_id,first_time_utc,last_time_utc,samples,samples_nonzero,ta_median,distance_median_m,ta_min,ta_max,rsrp_median_dbm", ue.get(0));
        assertTrue(ue.stream().anyMatch(s -> s.startsWith("12345,7,0101,1234,") && s.contains(",3,3,19.0,1484,19,19,-100.0")), ue.toString());
    }

    @Test
    void archivesAreUnpackedRecursively() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path out = tmp.resolve("out");
        byte[] stream = SampleStream.build(1);
        Files.write(in.resolve("a.zip"), zip("inner/ne3.lcbin_1", stream));
        sevenZ(in.resolve("b.7z"), "ne4.lcbin_1", stream);
        Files.write(in.resolve("c.zip"), zip("nested.zip", zip("deep/ne5.lcbin_1", stream)));
        Files.write(in.resolve("d.7z"), Files.readAllBytes(in.resolve("b.7z"))); // копия архива: своё имя вывода
        run(in, out);
        try (Stream<Path> s = Files.list(out)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(names.contains("a.zip_inner_ne3.lcbin_1.csv"), names.toString());
            assertTrue(names.contains("b.7z_ne4.lcbin_1.csv"), names.toString());
            assertTrue(names.contains("c.zip_nested.zip_deep_ne5.lcbin_1.csv"), names.toString());
            assertTrue(names.contains("d.7z_ne4.lcbin_1.csv"), names.toString());
            assertEquals(8, names.size());
        }
    }

    @Test
    void collidingOutputNamesGetSuffixes() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path out = tmp.resolve("out");
        byte[] stream = SampleStream.build(1);
        Files.write(in.resolve("x.zip"), zip("a/b.lcbin_1", stream));
        Files.write(in.resolve("x.zip_a_b.lcbin_1"), stream);   // та же основа имени, что у элемента архива
        run(in, out, "--no-summary");
        try (Stream<Path> s = Files.list(out)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("x.zip_a_b.lcbin_1.csv", "x.zip_a_b.lcbin_1~2.csv"), names);
        }
    }

    @Test
    void encryptedSevenZipFromResources() throws Exception {
        Path res = Path.of("src/test/resources/smoke");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(res), "smoke resources missing");
        Path out = tmp.resolve("out");
        run(res, out, "--password", "test");
        try (Stream<Path> s = Files.list(out)) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("sample-aes.7z_") && n.endsWith(".lcbin_1.csv")), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("sample.zip_")), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("nested.zip_inner.zip_")), names.toString());
        }
        // неверный пароль: ошибка архива -> код 1, но обычные файлы всё равно разобраны
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        int code = Main.run(Main.Options.parse(new String[]{res.toString(), tmp.resolve("bad").toString(), "--password", "wrong"}),
                new PrintStream(so), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(1, code);
        assertTrue(Files.exists(tmp.resolve("bad/sample.lcbin_1.csv")));
    }

    @Test
    void manyFilesFewThreadsDoesNotDeadlock() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path out = tmp.resolve("out");
        for (int i = 0; i < 40; i++) Files.write(in.resolve("ne" + i + ".lcbin_1"), SampleStream.build(50));
        run(in, out, "--threads", "1");
        run(in, tmp.resolve("out2"), "--threads", "3", "--no-summary");
        try (Stream<Path> s = Files.list(tmp.resolve("out2"))) { assertEquals(40, s.count()); }
    }

    @Test
    void snapshotStrategiesAndBlockSelection() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Files.write(in.resolve("neX_a.lcbin_10"), SampleStream.build(1));
        Files.write(in.resolve("neX_a.lcbin_20"), SampleStream.build(2));
        Files.write(in.resolve("neX_a.lcbin_30"), SampleStream.build(3));
        Files.write(in.resolve("neX_a.lcbin_0"), new byte[0]);
        run(in, tmp.resolve("median"), "--snapshots", "median", "--blocks", "ue-events");
        try (Stream<Path> s = Files.list(tmp.resolve("median"))) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("neX_a.lcbin_20.csv", "neX_a.lcbin_20.ue_ta.csv"), names.stream().sorted().toList());
        }
        List<String> l = lines(tmp.resolve("median/neX_a.lcbin_20.csv"));
        assertTrue(l.stream().skip(1).allMatch(s -> s.contains(",UE_EVENTS,") || s.contains("UNKNOWN_RECORD")), "only ue-events block decoded, rest dumped");
        run(in, tmp.resolve("largest"), "--snapshots", "largest");
        assertTrue(Files.exists(tmp.resolve("largest/neX_a.lcbin_30.csv")));
        assertEquals(SnapshotFilter.Strategy.ALL, Main.Options.parse(new String[]{"a", "b"}).snapshots());
        assertEquals(1, Main.Options.parse(new String[]{"a", "b", "--blocks", "status"}).registry().blocks().size());
        assertEquals(ParserRegistry.full().blocks().size(), Main.Options.parse(new String[]{"a", "b"}).registry().blocks().size());
    }

    @Test
    void usageErrors() {
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"onlyone"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"a", "b", "--bogus"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"a", "b", "--password"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"a", "b", "--blocks", "nope"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"a", "b", "--sep", ""}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"a", "b", "--threads", "0"}));
    }

    @Test
    void emptyInputReturnsError() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("empty"));
        int code = Main.run(Main.Options.parse(new String[]{in.toString(), tmp.resolve("o").toString()}),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(1, code);
    }
}
