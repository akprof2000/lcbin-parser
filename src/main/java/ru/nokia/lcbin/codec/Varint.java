package ru.nokia.lcbin.codec;

/**
 * Nokia-варинт: целое число переменной длины, которым в потоке lcbin закодированы почти все числа
 * (секунды, микросекунды, S1AP-идентификаторы, счётчики, Timing Advance).
 *
 * <p>Как устроено. Первый байт — тег. Его старшие два бита задают, сколько байт идёт дальше:
 * {@code 00} → 1 байт, {@code 01} → 2, {@code 10} → 3, {@code 11} → 4. Младшие шесть бит тега
 * являются старшими битами самого числа. Итого:
 * <pre>
 *   C0 68 D5 03 79  →  4 байта после тега, значение 0x68D50379 (Unix-время)
 *   80 0E 19 1F     →  3 байта, значение 0x0E191F = 923935 (микросекунды)
 *   40 B1 20        →  2 байта, значение 0xB120
 *   00 57           →  1 байт,  значение 0x57
 *   01 21           →  1 байт,  значение (0x01 << 8) | 0x21 = 0x121  (младшие биты тега не нулевые!)
 * </pre>
 * Кодер Nokia выбирает минимальную длину, при которой число помещается, поэтому один и тот же
 * тип поля (например микросекунды) в разных записях может занимать 1, 2 или 3 байта.
 */
public final class Varint {
    private Varint() {}

    /**
     * Результат чтения: само значение и смещение первого байта после варинта.
     * Смещение нужно, чтобы продолжить разбор записи с правильного места.
     */
    public record Result(long value, int nextOffset) {}

    /**
     * Читает варинт из массива {@code b} начиная с байта {@code offset}.
     *
     * @throws IllegalArgumentException если данных не хватает (запись обрезана)
     */
    public static Result read(byte[] b, int offset) {
        if (offset < 0 || offset >= b.length) {
            throw new IllegalArgumentException("varint truncated at offset " + offset);
        }
        int tag = b[offset] & 0xFF;
        int n = (tag >>> 6) + 1;                 // сколько байт после тега
        if (offset + n >= b.length) {
            throw new IllegalArgumentException("varint truncated at offset " + offset);
        }
        long v = tag & 0x3F;                     // младшие 6 бит тега — старшая часть числа
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (b[offset + 1 + k] & 0xFF);
        }
        return new Result(v, offset + 1 + n);
    }

    /** Возвращает true, если байт похож на «чистый» тег варинта (00/40/80/C0), т.е. младшие 6 бит нулевые. */
    public static boolean isPlainTag(byte[] b, int offset) {
        return (b[offset] & 0x3F) == 0;
    }
}
