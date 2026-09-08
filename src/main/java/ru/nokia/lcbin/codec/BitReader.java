package ru.nokia.lcbin.codec;

/**
 * Побитовый читатель для ASN.1 UPER (ITU-T X.691, unaligned PER) — так закодированы все RRC-сообщения 3GPP.
 *
 * <p>Идея UPER: поля не выравниваются по байтам, каждое занимает ровно столько бит, сколько нужно.
 * Например INTEGER (0..97) занимает 7 бит, BOOLEAN — 1 бит, CHOICE из 16 вариантов — 4 бита.
 * Поэтому читаем поток «бит за битом», начиная со старшего бита каждого байта.
 *
 * <p>Читатель работает на срезе массива {@code data[offset, offset+length)} и никогда не выходит за его
 * границы: при попытке прочитать лишнее бросается {@link IllegalStateException}, которую декодеры ловят
 * и превращают в предупреждение в CSV.
 */
public final class BitReader {
    private final byte[] data;
    private final int endBit;   // граница среза в битах
    private int pos;            // текущая позиция в битах от начала массива

    public BitReader(byte[] data) {
        this(data, 0, data.length);
    }

    public BitReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("BitReader: bad slice " + offset + "+" + length + " of " + data.length);
        }
        this.data = data;
        this.pos = offset * 8;
        this.endBit = (offset + length) * 8;
    }

    public int bitPosition() { return pos; }
    public int remainingBits() { return endBit - pos; }

    /** Один бит: 0 или 1. */
    public int readBit() {
        if (pos >= endBit) throw new IllegalStateException("UPER: read past end at bit " + pos);
        int v = (data[pos >>> 3] >>> (7 - (pos & 7))) & 1;
        pos++;
        return v;
    }

    public boolean readBoolean() { return readBit() == 1; }

    /** {@code n} бит как беззнаковое число (n ≤ 63). */
    public long readBits(int n) {
        if (n < 0 || n > 63) throw new IllegalArgumentException("readBits(" + n + ")");
        if (pos + n > endBit) throw new IllegalStateException("UPER: read past end at bit " + pos);
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 1) | readBit();
        return v;
    }

    /**
     * INTEGER (lo..hi) — X.691 10.5: значение кодируется как (value − lo) в минимальном числе бит,
     * достаточном для диапазона. Диапазон из одного значения не занимает бит вообще.
     */
    public long readConstrainedInt(long lo, long hi) {
        long range = hi - lo + 1;
        if (range <= 1) return lo;
        int bits = 64 - Long.numberOfLeadingZeros(range - 1);
        return lo + readBits(bits);
    }

    /** ENUMERATED без расширения: индекс 0..count-1. */
    public int readEnum(int count) {
        return (int) readConstrainedInt(0, count - 1);
    }

    /**
     * «Normally small non-negative whole number» (X.691 10.6) — используется для индекса варианта
     * CHOICE за маркером расширения и для числа групп расширения SEQUENCE.
     * Бит 0 + 6 бит для значений < 64, иначе бит 1 + обычный length determinant.
     */
    public int readNormallySmall() {
        if (readBit() == 0) return (int) readBits(6);
        return readLengthDeterminant();
    }

    /**
     * Length determinant (X.691 10.9) для неограниченных длин: 1 байт если < 128,
     * 2 байта (10xxxxxx yyyyyyyy) если < 16384. Фрагментация (>= 16k) в RRC не встречается.
     */
    public int readLengthDeterminant() {
        int b = (int) readBits(8);
        if ((b & 0x80) == 0) return b;
        if ((b & 0xC0) == 0x80) return ((b & 0x3F) << 8) | (int) readBits(8);
        throw new IllegalStateException("UPER: fragmented length not supported");
    }

    /** {@code n} октетов подряд (для OCTET STRING фиксированной длины и содержимого open type). */
    public byte[] readOctets(int n) {
        if (n < 0 || pos + n * 8L > endBit) throw new IllegalStateException("UPER: octets past end at bit " + pos);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) readBits(8);
        return out;
    }

    /** OCTET STRING без ограничения размера: length determinant + байты. */
    public byte[] readOctetString() {
        return readOctets(readLengthDeterminant());
    }

    /** BIT STRING фиксированной длины {@code n} бит как число (n ≤ 63). */
    public long readBitString(int n) {
        return readBits(n);
    }

    /**
     * Open type (X.691 10.2): length determinant + столько октетов. Используется для групп расширения
     * и для вариантов CHOICE за маркером расширения. Возвращает отдельный читатель на содержимое,
     * а позиция текущего читателя переходит за конец open type. Так ошибка внутри группы расширения
     * не ломает разбор остальных полей.
     */
    public BitReader readOpenType() {
        int len = readLengthDeterminant();
        if (pos + len * 8L > endBit) throw new IllegalStateException("UPER: open type past end at bit " + pos);
        // содержимое open type всегда начинается с границы октета в *своём* буфере, но в нашем потоке
        // оно может начинаться посреди байта — поэтому копируем биты в отдельный массив
        byte[] copy = new byte[len];
        for (int i = 0; i < len; i++) copy[i] = (byte) readBits(8);
        return new BitReader(copy);
    }

    /**
     * Пропускает группы расширения SEQUENCE, если маркер расширения был равен 1 (X.691 18.7–18.9):
     * число групп, битовая карта присутствия, затем каждая присутствующая группа как open type.
     */
    public void skipExtensionAdditions() {
        int n = readNormallySmall() + 1;
        boolean[] present = new boolean[n];
        for (int i = 0; i < n; i++) present[i] = readBoolean();
        for (int i = 0; i < n; i++) if (present[i]) readOpenType();
    }

    /**
     * Читает битовую карту групп расширения и возвращает её; сами группы читает вызывающий через
     * {@link #readOpenType()} по порядку для каждого {@code true}.
     */
    public boolean[] readExtensionBitmap() {
        int n = readNormallySmall() + 1;
        boolean[] present = new boolean[n];
        for (int i = 0; i < n; i++) present[i] = readBoolean();
        return present;
    }

    public void skipBits(int n) {
        if (n < 0 || pos + n > endBit) throw new IllegalStateException("UPER: skip past end at bit " + pos);
        pos += n;
    }
}
