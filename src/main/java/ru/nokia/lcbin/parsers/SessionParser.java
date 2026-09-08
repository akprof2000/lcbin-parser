package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.codec.Varint;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.List;

/**
 * Блок для записей 0x2E (начало трассировки UE) и 0x3C (конец).
 * <pre>
 *   type(1) recordSeq(2) const(2) GlobalENB-ID(7) TraceRef(6: PLMN + Trace ID) TraceSessionRef(2) ECGI(7)
 *   varint n_msgs (только в END; в START всегда 0)   varint sec   varint usec
 * </pre>
 * Trace Reference и Trace Recording Session Reference — термины 3GPP TS 32.422: первый один на соту,
 * второй увеличивается на 1 для каждого нового UE в соте. Те же значения UE потом присылает внутри
 * MDT-журнала, по ним можно связать журнал с сессией.
 */
public final class SessionParser implements RecordParser {
    @Override public String name() { return "session"; }
    @Override public boolean supports(int t) { return t == LcbinRecord.TYPE_SESSION_START || t == LcbinRecord.TYPE_SESSION_END; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        byte[] p = rec.payload();
        boolean start = rec.type() == LcbinRecord.TYPE_SESSION_START;
        Row row = new Row(rec, 0, start ? "TRACE_SESSION_START" : "TRACE_SESSION_END");
        try {
            if (p.length < 28) throw new IllegalStateException("short session record");
            row.put(Columns.RECORD_SEQ, Ids.u16(p, 1));
            row.put(Columns.PLMN, Ids.plmn(p, 5));
            row.put(Columns.ENB_ID, Ids.globalEnbId(p, 5));
            row.put(Columns.TRACE_REF, Ids.hex(p, 12, 6));
            row.put(Columns.TRACE_SESSION_REF, Ids.u16(p, 18));
            int eci = Ids.eci(p, 20);
            row.put(Columns.ECI, eci).put(Columns.CELL_ID, Ids.eciCell(eci));
            Varint.Result n = Varint.read(p, 27);
            if (!start) row.put(Columns.N_MSGS, n.value());
            Varint.Result sec = Varint.read(p, n.nextOffset());
            Varint.Result us = Varint.read(p, sec.nextOffset());
            row.put(Columns.TIME_UTC, Ids.timestamp(sec.value(), us.value()));
            if (us.nextOffset() != p.length) row.put(Columns.WARNING, "trailing bytes: " + Ids.hex(p, us.nextOffset(), p.length - us.nextOffset()));
        } catch (RuntimeException e) {
            row.put(Columns.WARNING, e.getMessage()).put(Columns.RAW_HEX, Ids.hex(p));
        }
        return List.of(row);
    }
}
