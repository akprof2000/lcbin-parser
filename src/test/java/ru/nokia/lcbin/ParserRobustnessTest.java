package ru.nokia.lcbin;

import org.junit.jupiter.api.Test;
import ru.nokia.lcbin.io.LcbinRecordReader;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.parsers.ParseContext;
import ru.nokia.lcbin.parsers.ParserRegistry;
import ru.nokia.lcbin.parsers.RecordDispatcher;
import ru.nokia.lcbin.parsers.RecordParser;
import ru.nokia.lcbin.rrc.RrcUlDcchDecoder;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsers must survive any input: truncated records, flipped bytes, random garbage.
 * Individual blocks are allowed to throw only RuntimeExceptions that the dispatcher converts into PARSE_ERROR rows;
 * the record reader and the RRC decoder must never throw at all.
 */
class ParserRobustnessTest {
    private static final String[] VECTORS = {SampleStream.HEADER, SampleStream.HEARTBEAT, SampleStream.SESSION_START,
            SampleStream.SESSION_END, SampleStream.UE_EVENTS, SampleStream.UE_EVENTS_SESSION, SampleStream.STATUS_SHORT, SampleStream.STATUS_LONG};

    private static LcbinRecord rec(byte[] payload) {
        return new LcbinRecord(0, 0, payload.length > 0 && payload[0] == 0x18 ? LcbinRecord.MARKER_STATUS : LcbinRecord.MARKER_MAIN,
                payload.length > 0 ? payload[0] & 0xFF : -1, payload);
    }

    @Test
    void everyPrefixOfEveryRecordIsHandled() {
        ParserRegistry reg = ParserRegistry.full();
        ParseContext ctx = ParseContext.of("fuzz");
        int rowsSeen = 0;
        for (String v : VECTORS) {
            byte[] full = SampleStream.hex(v);
            for (int len = 1; len <= full.length; len++) {
                byte[] cut = Arrays.copyOf(full, len);
                RecordParser block = reg.resolve(cut[0] & 0xFF);
                try {
                    List<Row> rows = block.parse(rec(cut), ctx);
                    assertNotNull(rows);
                    rowsSeen += rows.size();
                } catch (RuntimeException e) {
                    // tolerated for blocks; must be an ordinary runtime exception, not an Error
                    assertTrue(e instanceof IndexOutOfBoundsException || e instanceof IllegalArgumentException
                            || e instanceof IllegalStateException, "unexpected " + e + " for " + block.name() + " len=" + len);
                }
            }
        }
        assertTrue(rowsSeen > 0);
    }

    @Test
    void dispatcherTurnsBlockExceptionsIntoRows() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RecordDispatcher d = new RecordDispatcher(ParserRegistry.full(), pool, 3);
            byte[] bad = Arrays.copyOf(SampleStream.hex(SampleStream.UE_EVENTS), 40); // header cut inside items
            List<LcbinRecord> recs = List.of(
                    new LcbinRecord(0, 0, LcbinRecord.MARKER_MAIN, 0x48, bad),
                    new LcbinRecord(1, 0, LcbinRecord.MARKER_MAIN, 0x48, SampleStream.hex(SampleStream.UE_EVENTS)),
                    new LcbinRecord(2, 0, LcbinRecord.MARKER_MAIN, 0x3C, new byte[]{0x3C, 1}),
                    new LcbinRecord(3, 0, LcbinRecord.MARKER_STATUS, 0x18, new byte[]{0x18}),
                    new LcbinRecord(4, 0, LcbinRecord.MARKER_MAIN, 0x50, new byte[]{0x50, 0, 0}),
                    new LcbinRecord(5, 0, LcbinRecord.MARKER_MAIN, 0x60, new byte[]{0x60}));
            List<Row> rows = d.parseAll(recs, ParseContext.of("t"));
            assertTrue(rows.size() >= 6);
            assertEquals(0, rows.get(0).recordIndex());
            for (int i = 1; i < rows.size(); i++) assertTrue(rows.get(i).recordIndex() >= rows.get(i - 1).recordIndex(), "order kept");
            assertTrue(rows.stream().filter(r -> r.recordIndex() == 1).count() == 4, "good record fully parsed");
            assertTrue(rows.stream().filter(r -> r.recordIndex() != 1).allMatch(r -> r.get("warning") != null), "every damaged record carries a warning");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void randomByteFlipsNeverCrashTheStream() throws Exception {
        Random rnd = new Random(42);
        byte[] base = SampleStream.build(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RecordDispatcher d = new RecordDispatcher(ParserRegistry.full(), pool, 50);
            for (int iter = 0; iter < 300; iter++) {
                byte[] s = base.clone();
                int flips = 1 + rnd.nextInt(6);
                for (int k = 0; k < flips; k++) s[rnd.nextInt(s.length)] = (byte) rnd.nextInt(256);
                List<LcbinRecord> recs = new LcbinRecordReader().readAll(s);
                List<Row> rows = d.parseAll(recs, ParseContext.of("fuzz" + iter));
                assertNotNull(rows);
            }
            for (int iter = 0; iter < 100; iter++) {
                byte[] junk = new byte[rnd.nextInt(600)];
                rnd.nextBytes(junk);
                List<LcbinRecord> recs = new LcbinRecordReader().readAll(junk);
                d.parseAll(recs, ParseContext.of("junk"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recordReaderHandlesHostileLengths() {
        LcbinRecordReader r = new LcbinRecordReader();
        assertEquals(0, r.readAll(new byte[0]).size());
        assertEquals(1, r.readAll(new byte[]{1, 2, 3}).size());                       // short garbage
        List<LcbinRecord> huge = r.readAll(SampleStream.hex("4b16b055 ffffffff 60".replace(" ", "")));
        assertEquals(1, huge.size());
        assertEquals(-1, huge.get(0).type());                                          // length overflow -> gap
        List<LcbinRecord> zero = r.readAll(SampleStream.hex("0a0b0c0d 00000000 4b16b055 00000001 60".replace(" ", "")));
        assertTrue(zero.stream().anyMatch(x -> x.type() == 0x60), "resynchronised after zero-length status frame");
        List<LcbinRecord> mixed = r.readAll(SampleStream.hex("00 4b16b055 00000001 60 ff ff".replace(" ", "")));
        assertEquals(3, mixed.size());
        assertEquals(0x60, mixed.get(1).type());
    }

    @Test
    void rrcDecoderNeverThrows() {
        RrcUlDcchDecoder dec = new RrcUlDcchDecoder();
        Random rnd = new Random(7);
        for (int i = 0; i < 2000; i++) {
            byte[] b = new byte[rnd.nextInt(24)];
            rnd.nextBytes(b);
            assertNotNull(dec.decode(b).name());
        }
        assertEquals("undecodable", dec.decode(new byte[0]).name());
        byte[] meas = SampleStream.MEAS_B;
        for (int len = 1; len < meas.length; len++) assertNotNull(dec.decode(Arrays.copyOf(meas, len)).name());
    }
}
