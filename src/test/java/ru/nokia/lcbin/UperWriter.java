package ru.nokia.lcbin;

import java.io.ByteArrayOutputStream;

/**
 * Минимальный UPER-кодер для тестов — зеркало {@code BitReader}. Нужен, чтобы собирать синтетические
 * RRC-сообщения с известными значениями и проверять декодер «круговым» тестом (encode → decode → сравнить),
 * не используя реальные данные операторов.
 */
final class UperWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int cur;   // накапливаемый байт
    private int nbits; // сколько бит уже в cur

    UperWriter bit(int b) {
        cur = (cur << 1) | (b & 1);
        if (++nbits == 8) { out.write(cur); cur = 0; nbits = 0; }
        return this;
    }

    UperWriter bool(boolean b) { return bit(b ? 1 : 0); }

    UperWriter bits(long v, int n) {
        for (int i = n - 1; i >= 0; i--) bit((int) ((v >>> i) & 1));
        return this;
    }

    /** INTEGER (lo..hi): (v − lo) в минимальном числе бит. */
    UperWriter cint(long v, long lo, long hi) {
        long range = hi - lo + 1;
        if (range <= 1) return this;
        int n = 64 - Long.numberOfLeadingZeros(range - 1);
        return bits(v - lo, n);
    }

    /** Normally small number (X.691 10.6) для < 64. */
    UperWriter small(int v) { return bit(0).bits(v, 6); }

    UperWriter lengthDet(int len) {
        if (len < 128) return bits(len, 8);
        return bit(1).bit(0).bits(len, 14);
    }

    UperWriter octets(byte[] b) {
        for (byte x : b) bits(x & 0xFF, 8);
        return this;
    }

    /** OCTET STRING без ограничения: длина + байты. */
    UperWriter octetString(byte[] b) { return lengthDet(b.length).octets(b); }

    /** Open type: содержимое (уже выровненное до байта) с length determinant. */
    UperWriter openType(UperWriter content) { return octetString(content.toBytes()); }

    /** Дописывает нули до границы байта и отдаёт результат. */
    byte[] toBytes() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        if (nbits > 0) copy.write(cur << (8 - nbits));
        return copy.toByteArray();
    }
}
