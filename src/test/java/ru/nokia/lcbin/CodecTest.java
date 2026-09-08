package ru.nokia.lcbin;

import org.junit.jupiter.api.Test;
import ru.nokia.lcbin.codec.BitReader;
import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.codec.Varint;
import ru.nokia.lcbin.io.LcbinRecordReader;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.parsers.ParseContext;
import ru.nokia.lcbin.parsers.UeEventsParser;
import ru.nokia.lcbin.rrc.RrcUlDcchDecoder;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Кодеки и разбор записи 0x48 на синтетических данных. */
class CodecTest {
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s.replace(" ", "")); }

    @Test
    void varintLengths() {
        assertEquals(0x6553f100L, Varint.read(hex("c06553f100"), 0).value());
        assertEquals(0x01e240L, Varint.read(hex("8001e240"), 0).value());
        assertEquals(0x1234L, Varint.read(hex("401234"), 0).value());
        assertEquals(0x57L, Varint.read(hex("0057"), 0).value());
        assertEquals(0x121L, Varint.read(hex("0121"), 0).value()); // младшие биты тега входят в значение
        assertEquals(2, Varint.read(hex("0057"), 0).nextOffset());
        assertThrows(IllegalArgumentException.class, () -> Varint.read(hex("c000"), 0));
        assertThrows(IllegalArgumentException.class, () -> Varint.read(hex("00"), 1));
    }

    @Test
    void identifiers() {
        assertEquals("001-01", Ids.plmn(hex(SampleStream.PLMN_HEX), 0));
        assertEquals("262-01", Ids.plmn(hex("62f210"), 0));           // 2-значный MNC
        assertEquals("310-410", Ids.plmn(hex("130014"), 0));          // 3-значный MNC
        assertEquals(SampleStream.ENB, Ids.globalEnbId(hex(SampleStream.GLOBAL_ENB), 0));
        int eci = Ids.eci(hex(SampleStream.ECGI), 0);
        assertEquals(SampleStream.ENB, Ids.eciEnb(eci));
        assertEquals(SampleStream.CELL, Ids.eciCell(eci));
        assertEquals("2023-11-14 22:13:20.123456", Ids.timestamp(SampleStream.SEC, 123456));
    }

    @Test
    void uperPrimitives() {
        BitReader r = new BitReader(hex("a0")); // 101 00000
        assertEquals(5, r.readConstrainedInt(0, 7));
        assertEquals(8, new BitReader(hex("80")).readConstrainedInt(7, 8));   // диапазон из 2 значений = 1 бит
        assertEquals(42, new BitReader(hex("00")).readConstrainedInt(42, 42)); // диапазон из 1 значения = 0 бит
        BitReader open = new BitReader(hex("02 ab cd ff"));
        BitReader inner = open.readOpenType();
        assertEquals(16, inner.remainingBits());
        assertEquals(0xab, inner.readBits(8));
        assertEquals(0xff, open.readBits(8));
        assertThrows(IllegalStateException.class, () -> new BitReader(hex("00")).readBits(9));
        BitReader small = new BitReader(hex("04")); // 0 000010 0 → normally small = 2
        assertEquals(2, small.readNormallySmall());
    }

    @Test
    void measurementReportRoundTrip() {
        RrcUlDcchDecoder.Message m = new RrcUlDcchDecoder().decode(SampleStream.MEAS_A);
        assertEquals("measurementReport", m.name());
        assertNull(m.warning());
        var mr = m.measurementReport();
        assertEquals(5, mr.measId());
        assertEquals(-100.0, mr.rsrpDbm());
        assertEquals(-10.0, mr.rsrqDb());
        assertEquals(1, mr.neighbors().size());
        assertEquals("pci88:-105dBm/-13.5dB", mr.neighbors().get(0).format());
        assertNull(mr.geo());
        assertTrue(mr.scells().isEmpty());
    }

    @Test
    void ueEventsRecordWithTaAndMeas() {
        LcbinRecord rec = new LcbinRecord(0, 0, LcbinRecord.MARKER_MAIN, 0x48, hex(SampleStream.UE_EVENTS));
        List<Row> rows = new UeEventsParser().parse(rec, ParseContext.of("test"));
        assertEquals(4, rows.size());
        assertEquals("UE_RRC", rows.get(0).get("event"));
        assertEquals(-100.0, rows.get(0).get("rsrp_dbm"));
        assertEquals("2023-11-14 22:13:20.123456", rows.get(0).get("time_utc"));
        assertEquals("UE_TA", rows.get(1).get("event"));
        assertEquals(19L, rows.get(1).get("timing_advance"));
        assertEquals(1484L, rows.get(1).get("distance_m"));
        assertEquals(SampleStream.RNTI_A, rows.get(1).get("c_rnti"));
        assertEquals(SampleStream.CELL, rows.get(0).get("cell_id"));
        assertEquals(SampleStream.ENB, rows.get(0).get("enb_id"));
        assertEquals("UE_RRC", rows.get(2).get("event"));
        assertEquals(10.5, ((Double) rows.get(2).get("lat")).doubleValue(), 1e-5);
        assertEquals(-20.25, ((Double) rows.get(2).get("lon")).doubleValue(), 1e-5);
        assertEquals(30, rows.get(2).get("alt_m"));
        assertEquals(123456L, rows.get(2).get("gnss_tod_ms"));
        assertEquals("sf1:-95dBm/-12dB", rows.get(2).get("scell_results"));
        assertEquals(7L, rows.get(3).get("timing_advance"));
        for (Row r : rows) assertNull(r.get("warning"));
    }

    @Test
    void ueEventsSessionItemWithShortUsec() {
        LcbinRecord rec = new LcbinRecord(0, 0, LcbinRecord.MARKER_MAIN, 0x48, hex(SampleStream.UE_EVENTS_SESSION));
        List<Row> rows = new UeEventsParser().parse(rec, ParseContext.of("test"));
        assertEquals(1, rows.size());
        Row r = rows.get(0);
        assertEquals("UE_SESSION+TA", r.get("event"));
        assertEquals(100000L, r.get("enb_ue_s1ap_id"));
        assertEquals(5000000L, r.get("mme_ue_s1ap_id"));
        assertEquals("001-01", r.get("tai_plmn"));
        assertEquals(100, r.get("tac"));
        assertEquals("30", r.get("session_attr"));
        assertEquals(5L, r.get("timing_advance"));
        assertEquals("2023-11-14 22:13:22.004660", r.get("time_utc"));
        assertNull(r.get("warning"));
    }

    @Test
    void recordReaderFramesBothMarkers() {
        byte[] stream = hex("4b16b055 00000002 6001" + "0a0b0c0d 0000000a 1801" + "4b16b055 00000001 50");
        List<LcbinRecord> recs = new LcbinRecordReader().readAll(stream);
        assertEquals(3, recs.size());
        assertEquals(0x60, recs.get(0).type());
        assertEquals(0x18, recs.get(1).type());
        assertEquals(2, recs.get(1).payload().length);
        assertEquals(0x50, recs.get(2).type());
        assertEquals(20, recs.get(2).offset());
        assertEquals("GAP", LcbinRecord.typeName(-1));
    }
}
