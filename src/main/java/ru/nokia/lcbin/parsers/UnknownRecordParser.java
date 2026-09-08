package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.List;

/**
 * Запасной блок: пропуски между маркерами (тип -1) и типы записей, которые никто не взял,
 * выгружаются в hex. Ничего не теряется — можно потом посмотреть, что это было.
 */
public final class UnknownRecordParser implements RecordParser {
    @Override public String name() { return "unknown"; }
    @Override public boolean supports(int t) { return true; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        Row row = new Row(rec, 0, rec.type() < 0 ? "FRAMING_GAP" : "UNKNOWN_RECORD");
        row.put(Columns.RAW_HEX, Ids.hex(rec.payload()));
        row.put(Columns.WARNING, rec.type() < 0 ? "unframed bytes, resynchronised on next marker" : "unknown record type");
        return List.of(row);
    }
}
