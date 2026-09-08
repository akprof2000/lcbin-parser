package ru.nokia.lcbin.model;

/**
 * Одна «обрамлённая» запись потока lcbin — то, что {@code LcbinRecordReader} вырезает между маркерами.
 *
 * <p>Поток состоит из записей двух видов:
 * <pre>
 *   4B 16 B0 55  len(u32 BE)  payload      — len = длина payload (без 8-байтного заголовка)
 *   0A 0B 0C 0D  len(u32 BE)  payload      — len = длина ВКЛЮЧАЯ 8-байтный заголовок
 * </pre>
 * Первый байт payload — тип записи (см. константы TYPE_*).
 *
 * @param index   порядковый номер записи в файле (с нуля) — по нему восстанавливается порядок строк CSV
 * @param offset  смещение заголовка записи в файле (для отладки: можно открыть hex-редактор и посмотреть)
 * @param marker  какой маркер обрамлял запись
 * @param type    тип записи (первый байт payload) или -1 для «мусора» между маркерами
 * @param payload байты записи, включая байт типа в позиции 0
 */
public record LcbinRecord(int index, long offset, int marker, int type, byte[] payload) {
    public static final int MARKER_MAIN = 0x4B16B055;
    public static final int MARKER_STATUS = 0x0A0B0C0D;

    /** 0x60 — заголовок потока: Global eNB ID и версия. Первая запись в файле. */
    public static final int TYPE_FILE_HEADER = 0x60;
    /** 0x50 — heartbeat раз в ~20 с, только время. */
    public static final int TYPE_HEARTBEAT = 0x50;
    /** 0x2E — начало трассировки конкретного UE (Trace Recording Session). */
    public static final int TYPE_SESSION_START = 0x2E;
    /** 0x3C — конец трассировки UE, содержит число сообщений в сессии. */
    public static final int TYPE_SESSION_END = 0x3C;
    /** 0x48 — пачка событий UE: MeasurementReport, Timing Advance, идентификаторы S1AP. Главная запись. */
    public static final int TYPE_UE_EVENTS = 0x48;
    /** 0x18 (маркер 0A0B0C0D) — статус; длинный вариант несёт UEInformationResponse (RACH/MDT/RLF-отчёты). */
    public static final int TYPE_STATUS = 0x18;

    /** Человекочитаемое имя типа для колонки record_type. */
    public static String typeName(int type) {
        if (type < 0) return "GAP";
        return switch (type) {
            case TYPE_FILE_HEADER -> "FILE_HEADER";
            case TYPE_HEARTBEAT -> "HEARTBEAT";
            case TYPE_SESSION_START -> "SESSION_START";
            case TYPE_SESSION_END -> "SESSION_END";
            case TYPE_UE_EVENTS -> "UE_EVENTS";
            case TYPE_STATUS -> "STATUS";
            default -> String.format("UNKNOWN_%02X", type);
        };
    }
}
