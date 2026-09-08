package ru.nokia.lcbin.codec;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Декодеры идентификаторов 3GPP, которые Nokia кладёт в записи в кодировке S1AP (aligned PER):
 * PLMN, Global eNB ID, ECGI, а также вспомогательные функции для чисел, hex и времени.
 */
public final class Ids {
    private Ids() {}

    private static final HexFormat HEX = HexFormat.of();

    /** Формат времени в CSV: UTC с микросекундами, например {@code 2025-09-25 08:55:21.923935}. */
    public static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    /**
     * PLMN identity — 3 байта BCD по TS 24.008 10.5.1.3. Цифры лежат «полубайтами» в перевёрнутом порядке:
     * <pre>
     *   байт0 = MCC2 | MCC1,  байт1 = MNC3 | MCC3,  байт2 = MNC2 | MNC1
     *   52 F0 20  →  MCC 250, MNC3 = F (нет третьей цифры) → MNC 02  →  "250-02"
     * </pre>
     */
    public static String plmn(byte[] b, int off) {
        int[] d = {b[off] & 15, (b[off] >> 4) & 15, b[off + 1] & 15, (b[off + 1] >> 4) & 15,
                   b[off + 2] & 15, (b[off + 2] >> 4) & 15};
        String mcc = "" + d[0] + d[1] + d[2];
        String mnc = d[3] == 15 ? "" + d[4] + d[5] : "" + d[4] + d[5] + d[3];
        return mcc + "-" + mnc;
    }

    /**
     * Global eNB ID (7 байт): PLMN(3) + байт выбора CHOICE (00 = macro eNB) + 20-битный идентификатор eNB,
     * выровненный по левому краю в 3 байтах. Пример {@code 52F020 00 07DAA0} → 0x7DAA = 32170.
     */
    public static int globalEnbId(byte[] b, int off) {
        int v = u32(b, off + 3);
        return (v >>> 4) & 0xFFFFF;
    }

    /**
     * ECGI (7 байт): PLMN(3) + 28-битный E-UTRAN Cell Identity, выровненный по левому краю в 4 байтах.
     * ECI = (eNB ID << 8) | cellId. Пример {@code 52F020 07DAA0A0} → ECI 0x7DAA0A → eNB 32170, сота 10.
     */
    public static int eci(byte[] b, int off) {
        return u32(b, off + 3) >>> 4;
    }

    public static int eciEnb(int eci) { return eci >>> 8; }
    public static int eciCell(int eci) { return eci & 0xFF; }

    /** 4 байта big-endian как int (для длин и идентификаторов). */
    public static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 2 байта big-endian как int. */
    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static String hex(byte[] b, int off, int len) {
        return HEX.formatHex(b, off, off + len);
    }

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    /** Unix-секунды + микросекунды → строка времени UTC. */
    public static String timestamp(long sec, long usec) {
        return TS_FMT.format(Instant.ofEpochSecond(sec, usec * 1000L));
    }
}
