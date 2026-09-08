package ru.nokia.lcbin.parsers;

/**
 * Настройки разбора, общие для всех блоков в рамках одного входного файла.
 *
 * @param sourceName имя разбираемого файла (для диагностики)
 * @param taMetres   метров в одной единице Timing Advance: 16·Ts = 0.52 мкс туда-обратно → 78.125 м в одну сторону
 * @param keepRawHex писать hex успешно декодированных RRC-тел в колонку rrc_hex (для отладки)
 */
public record ParseContext(String sourceName, double taMetres, boolean keepRawHex) {
    /** c · 16 · Ts / 2, где Ts = 1 / 30.72 МГц. */
    public static final double TA_METRES = 78.125;

    public static ParseContext of(String sourceName) {
        return new ParseContext(sourceName, TA_METRES, false);
    }
}
