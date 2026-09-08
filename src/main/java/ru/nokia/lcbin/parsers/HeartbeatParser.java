package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.codec.Varint;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.List;

/**
 * Блок для записи 0x50 — heartbeat, приходит примерно раз в 20 секунд и несёт только время eNodeB.
 * <pre>  50  GlobalENB-ID(7, младший полубайт = C)  sec(u32, без тега!)  varint usec </pre>
 * Полезен, чтобы понять, что соединение с eNodeB было живо, даже когда абонентов не было.
 */
public final class HeartbeatParser implements RecordParser {
    @Override public String name() { return "heartbeat"; }
    @Override public boolean supports(int t) { return t == LcbinRecord.TYPE_HEARTBEAT; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        byte[] p = rec.payload();
        Row row = new Row(rec, 0, "HEARTBEAT");
        try {
            if (p.length < 13) throw new IllegalStateException("short heartbeat");
            row.put(Columns.PLMN, Ids.plmn(p, 1));
            row.put(Columns.ENB_ID, Ids.globalEnbId(p, 1));
            long sec = Ids.u32(p, 8) & 0xFFFFFFFFL;
            Varint.Result us = Varint.read(p, 12);
            row.put(Columns.TIME_UTC, Ids.timestamp(sec, us.value()));
            if (us.nextOffset() != p.length) row.put(Columns.WARNING, "trailing bytes: " + Ids.hex(p, us.nextOffset(), p.length - us.nextOffset()));
        } catch (RuntimeException e) {
            row.put(Columns.WARNING, e.getMessage()).put(Columns.RAW_HEX, Ids.hex(p));
        }
        return List.of(row);
    }
}
