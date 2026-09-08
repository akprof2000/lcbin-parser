package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.List;

/**
 * Блок для записи 0x60 — заголовок потока, всегда первая запись файла.
 * <pre>  60  GlobalENB-ID(7)  version(6, например 01 02 01 11 01 00) </pre>
 * По нему узнаём, с какого eNodeB пришёл поток, ещё до первого события.
 */
public final class FileHeaderParser implements RecordParser {
    @Override public String name() { return "file-header"; }
    @Override public boolean supports(int t) { return t == LcbinRecord.TYPE_FILE_HEADER; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        byte[] p = rec.payload();
        Row row = new Row(rec, 0, "FILE_HEADER");
        if (p.length < 8) return List.of(row.put(Columns.WARNING, "short header").put(Columns.RAW_HEX, Ids.hex(p)));
        row.put(Columns.PLMN, Ids.plmn(p, 1));
        row.put(Columns.ENB_ID, Ids.globalEnbId(p, 1));
        row.put(Columns.VERSION, Ids.hex(p, 8, p.length - 8));
        return List.of(row);
    }
}
