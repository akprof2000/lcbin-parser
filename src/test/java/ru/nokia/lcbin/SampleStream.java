package ru.nokia.lcbin;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

/**
 * Полностью синтетический поток lcbin для тестов. Ни одного байта реальных трасс: PLMN 001-01,
 * eNB 12345, сота 7, вымышленные RNTI, идентификаторы S1AP и координаты (точка в океане).
 * Раскладка записей повторяет docs/FORMAT.md.
 */
public final class SampleStream {
    private SampleStream() {}

    public static final String PLMN_HEX = "00f110";                    // 001-01
    public static final int ENB = 12345;                                // 0x3039
    public static final int CELL = 7;
    public static final String GLOBAL_ENB = PLMN_HEX + "00030390";      // 20 бит 0x03039 << 4
    public static final String ECGI = PLMN_HEX + "03039070";            // ECI 0x303907 << 4
    public static final String TRACE_REF = PLMN_HEX + "000001";
    public static final long SEC = 1700000000L;                         // 2023-11-14 22:13:20 UTC
    public static final int RNTI_A = 1234;
    public static final int RNTI_B = 4321;
    public static final SyntheticRrc.Point POINT = new SyntheticRrc.Point(10.5, -20.25, 30);

    public static final String HEADER = "60" + GLOBAL_ENB + "010201110100";
    public static final String HEARTBEAT = "50" + PLMN_HEX + "0003039c" + "6553f100" + "80000064";
    public static final String SESSION_START = "2e0001120b" + GLOBAL_ENB + TRACE_REF + "0001" + ECGI + "0000" + "c06553f100" + "80000064";
    public static final String SESSION_END = "3c0003120b" + GLOBAL_ENB + TRACE_REF + "0001" + ECGI + "0003" + "c06553f110" + "80000064";
    public static final String STATUS_SHORT = "18010f0d00" + PLMN_HEX + "00003039" + "0000" + "01" + "00" + "0002" + "120b" + "00000000";

    /** MeasurementReport UE A: measId 5, RSRP −100 dBm (idx 40), RSRQ −10 dB (idx 19), сосед pci 88. */
    public static final byte[] MEAS_A = SyntheticRrc.measurementReport(5, 40, 19, List.of(new SyntheticRrc.Eutra(88, 35, 12)), null, null, null);
    /** MeasurementReport UE B: с координатами, GNSS TOD и одной SCell. */
    public static final byte[] MEAS_B = SyntheticRrc.measurementReport(5, 50, 20, List.of(), POINT, 123456L, new int[][]{{1, 45, 15}});
    /** UEInformationResponse: rach (2 преамбулы, коллизия), rlf, MDT-журнал из 2 записей. */
    public static final byte[] UE_INFO = SyntheticRrc.ueInformationResponse(2, true, true, "231114221320", List.of(
            new SyntheticRrc.LogEntry(POINT, 10, ENB, CELL, 38, 14, List.of(new SyntheticRrc.Eutra(88, 30, 10)), 1300,
                    new int[][]{{100, 25, 40}}, 10700, new int[][]{{68, 1, 3, 49}}),
            new SyntheticRrc.LogEntry(null, 20, ENB, CELL, 36, 13, List.of(), 0, null, 0, null)));

    /** Запись 0x48 с 4 элементами: (MEAS_A, TA 19) для UE A и (MEAS_B, TA 7) для UE B. */
    public static final String UE_EVENTS = hex(ueEventsRecord());
    /** Запись 0x48 с одним элементом: первый элемент UE (сессионный префикс) + TA 5, микросекунды в 2 байтах. */
    public static final String UE_EVENTS_SESSION = hex(ueEventsSessionRecord());
    /** Длинная запись 0x18 с UEInformationResponse. */
    public static final String STATUS_LONG = hex(statusLongRecord());

    public static byte[] hex(String h) { return HexFormat.of().parseHex(h); }
    public static String hex(byte[] b) { return HexFormat.of().formatHex(b); }

    private static byte[] item(int bitmap, int ueId, int ctr, long sec, int usec3, int rnti, byte[] session, byte[] rrc, Integer ta) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(bitmap);
        b.write(ueId >> 8); b.write(ueId & 0xFF);
        b.write(0x24); b.write(0x16);
        b.write(ctr >> 8); b.write(ctr & 0xFF);
        b.write(0xC0); b.write((int) (sec >> 24)); b.write((int) (sec >> 16) & 0xFF); b.write((int) (sec >> 8) & 0xFF); b.write((int) sec & 0xFF);
        if (usec3 >= 65536) { b.write(0x80); b.write(usec3 >> 16); b.write((usec3 >> 8) & 0xFF); b.write(usec3 & 0xFF); }
        else { b.write(0x40); b.write(usec3 >> 8); b.write(usec3 & 0xFF); }
        b.write(rnti >> 8); b.write(rnti & 0xFF);
        if (session != null) b.writeBytes(session);
        if (rrc != null) { b.write(0); b.write(0); b.write(0x7B); b.write(rrc.length); b.writeBytes(rrc); }
        if (ta != null) { b.write(0x61); b.write(0); b.write(0); b.write(ta); }   // 61 00 + varint(00 xx)
        return b.toByteArray();
    }

    private static byte[] header48(int count) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x48); b.write(0x00); b.write(0x05); b.write(0x12); b.write(0x0B);
        b.writeBytes(hex(GLOBAL_ENB)); b.writeBytes(hex(TRACE_REF)); b.writeBytes(hex(ECGI));
        b.write(count);
        return b.toByteArray();
    }

    private static byte[] ueEventsRecord() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(header48(4));
        b.writeBytes(item(0x62, 0x0101, 5, SEC, 123456, RNTI_A, null, MEAS_A, null));
        b.writeBytes(item(0x62, 0x0101, 6, SEC, 123456, RNTI_A, null, null, 19));
        b.writeBytes(item(0x62, 0x0102, 5, SEC + 1, 200000, RNTI_B, null, MEAS_B, null));
        b.writeBytes(item(0x62, 0x0102, 6, SEC + 1, 200000, RNTI_B, null, null, 7));
        return b.toByteArray();
    }

    private static byte[] ueEventsSessionRecord() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(header48(1));
        // eNB-UE-S1AP-ID 100000 (80 01 86 A0), MME-UE-S1AP-ID 5000000 (C0 00 4C 4B 40), TAI 001-01/100, attr 30
        byte[] session = hex("800186a0" + "c0004c4b40" + PLMN_HEX + "0064" + "30");
        b.writeBytes(item(0x7E, 0x0103, 1, SEC + 2, 4660, 777, session, null, 5));
        return b.toByteArray();
    }

    private static byte[] statusLongRecord() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(hex("18010f0d00" + PLMN_HEX + "00003039" + "0000" + "11" + "00" + "0004" + "120b" + "00000000"));
        b.writeBytes(hex(TRACE_REF));
        b.writeBytes(hex("00000000"));
        int rest = 4 + 4 + 4 + 2 + 6 + 4 + 4 + 4 + UE_INFO.length;
        b.write(rest >> 24); b.write((rest >> 16) & 0xFF); b.write((rest >> 8) & 0xFF); b.write(rest & 0xFF);
        b.writeBytes(hex("6553f105"));               // sec = SEC + 5
        b.writeBytes(hex("00000064"));               // usec 100
        b.writeBytes(hex("0002"));                   // traceRecordingSessionRef
        b.writeBytes(hex("018404060116"));
        b.writeBytes(hex("00303907"));               // ECI
        b.writeBytes(hex("000186a0"));               // eNB-UE-S1AP-ID 100000
        b.writeBytes(hex("00000309"));               // C-RNTI 777
        b.writeBytes(UE_INFO);
        return b.toByteArray();
    }

    /** Обрамляет payload маркером: main (длина без заголовка) или status (длина с заголовком). */
    public static byte[] frame(byte[] payload) {
        boolean status = payload[0] == 0x18;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int marker = status ? 0x0A0B0C0D : 0x4B16B055;
        int len = status ? payload.length + 8 : payload.length;
        for (int s = 24; s >= 0; s -= 8) b.write((marker >>> s) & 0xFF);
        for (int s = 24; s >= 0; s -= 8) b.write((len >>> s) & 0xFF);
        b.writeBytes(payload);
        return b.toByteArray();
    }

    /** Полный поток с {@code repeat} парами записей событий (каждая пара = 4 + 1 элемент). */
    public static byte[] build(int repeat) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(frame(hex(HEADER)));
        b.writeBytes(frame(hex(HEARTBEAT)));
        b.writeBytes(frame(hex(SESSION_START)));
        for (int i = 0; i < repeat; i++) {
            b.writeBytes(frame(hex(UE_EVENTS)));
            b.writeBytes(frame(hex(UE_EVENTS_SESSION)));
        }
        b.writeBytes(frame(hex(STATUS_SHORT)));
        b.writeBytes(frame(hex(STATUS_LONG)));
        b.writeBytes(frame(hex(SESSION_END)));
        return b.toByteArray();
    }

    public static int recordCount(int repeat) { return 6 + 2 * repeat; }
}
