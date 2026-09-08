package ru.nokia.lcbin.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Одна строка выходного CSV: упорядоченный набор «имя колонки → значение».
 *
 * <p>Парсер кладёт только известные ему поля через {@link #put}; {@code null} игнорируется,
 * поэтому можно писать {@code row.put(X, maybeNull)} без проверок. Писатель CSV сам выстраивает
 * значения по общей схеме {@link Columns#ALL}, пропущенные колонки остаются пустыми.
 *
 * <p>{@code recordIndex} и {@code subIndex} нужны диспетчеру, чтобы после параллельного разбора
 * вернуть строки в исходном порядке потока (запись → элемент внутри записи).
 */
public final class Row {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final int recordIndex;
    private final int subIndex;

    /** Строка, порождённая записью потока: индекс/смещение/тип записи заполняются автоматически. */
    public Row(LcbinRecord rec, int subIndex, String eventKind) {
        this.recordIndex = rec == null ? -1 : rec.index();
        this.subIndex = subIndex;
        if (rec != null) {
            values.put(Columns.RECORD_INDEX, rec.index());
            values.put(Columns.RECORD_OFFSET, rec.offset());
            values.put(Columns.RECORD_TYPE, LcbinRecord.typeName(rec.type()));
        }
        values.put(Columns.EVENT, eventKind);
    }

    /** Строка без привязки к записи (сводки, агрегаты). */
    public Row(String eventKind) {
        this(null, 0, eventKind);
    }

    /** Копия всех значений другой строки (для «размножения» одной записи в несколько строк). */
    public Row copyFrom(Row other) {
        for (Map.Entry<String, Object> e : other.values.entrySet()) {
            if (!Columns.EVENT.equals(e.getKey())) values.put(e.getKey(), e.getValue());
        }
        return this;
    }

    public Row put(String column, Object value) {
        if (value != null) values.put(column, value);
        return this;
    }

    public Object get(String column) { return values.get(column); }
    public Map<String, Object> values() { return values; }
    public int recordIndex() { return recordIndex; }
    public int subIndex() { return subIndex; }
}
