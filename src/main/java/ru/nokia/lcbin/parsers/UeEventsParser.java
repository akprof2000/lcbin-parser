package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.codec.Varint;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.rrc.Geo;
import ru.nokia.lcbin.rrc.RrcUlDcchDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Блок для записи 0x48 — пачка событий по абонентам (UE). Это главная запись: именно здесь лежат
 * Timing Advance и MeasurementReport.
 *
 * <p>Раскладка записи:
 * <pre>
 *   48  recordSeq(2)  const(2)  GlobalENB-ID(7)  TraceRef(6)  ECGI(7)  count(1)  затем count элементов
 *
 *   элемент:
 *     bitmap(1)  ue_id(2)  const(1)  16  ue_ctr(2)  varint sec  varint usec  C-RNTI(2)  под-поля...
 *
 *   под-поля (в любом наборе, по порядку):
 *     [только если bitmap = 7E/7F, т.е. первый элемент для этого UE]
 *       varint eNB-UE-S1AP-ID   varint MME-UE-S1AP-ID   TAI(5: PLMN + TAC)   attr(1)
 *     00 00 7B len <RRC UL-DCCH в UPER>      — MeasurementReport
 *     61 00 <varint TA>                       — абсолютный Timing Advance (единица 16·Ts = 78.125 м)
 * </pre>
 * Каждый элемент даёт одну строку CSV. TA приходит отдельным элементом сразу после MeasurementReport
 * того же UE с той же меткой времени — поэтому в CSV они соседние строки с одинаковым time_utc.
 *
 * <p>Бит 0x10 в bitmap надёжно показывает наличие «сессионного» префикса (проверено на 94 тыс. элементов:
 * 7E/7F ⇔ префикс есть, 62/63 ⇔ нет). Полагаться на тег варинта нельзя: маленький S1AP-ID закодируется тегом 00.
 */
public final class UeEventsParser implements RecordParser {
    private final RrcUlDcchDecoder rrc = new RrcUlDcchDecoder();

    @Override public String name() { return "ue-events"; }
    @Override public boolean supports(int t) { return t == LcbinRecord.TYPE_UE_EVENTS; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        byte[] p = rec.payload();
        List<Row> rows = new ArrayList<>();
        if (p.length < 26) {
            return List.of(new Row(rec, 0, "UE_EVENT").put(Columns.WARNING, "short record").put(Columns.RAW_HEX, Ids.hex(p)));
        }
        int recordSeq = Ids.u16(p, 1);
        String plmn = Ids.plmn(p, 5);
        int enb = Ids.globalEnbId(p, 5);
        String traceRef = Ids.hex(p, 12, 6);
        int eci = Ids.eci(p, 18);
        int count = p[25] & 0xFF;
        int q = 26;
        int sub = 0;
        for (int i = 0; i < count && q < p.length; i++) {
            Row row = new Row(rec, sub++, "UE_EVENT");
            row.put(Columns.RECORD_SEQ, recordSeq).put(Columns.PLMN, plmn).put(Columns.ENB_ID, enb)
               .put(Columns.TRACE_REF, traceRef).put(Columns.ECI, eci).put(Columns.CELL_ID, Ids.eciCell(eci));
            try {
                q = parseItem(p, q, row, ctx);
            } catch (RuntimeException e) {
                // элемент обрезан или повреждён: отдаём что есть и остаток записи в hex, дальше не идём
                row.put(Columns.WARNING, "item parse error: " + e.getMessage())
                   .put(Columns.RAW_HEX, Ids.hex(p, q, p.length - q));
                rows.add(row);
                return rows;
            }
            rows.add(row);
        }
        if (q != p.length || rows.size() != count) {
            Row w = new Row(rec, sub, "UE_EVENT_LEFTOVER");
            w.put(Columns.WARNING, "items " + rows.size() + "/" + count + ", " + (p.length - q) + " bytes left")
             .put(Columns.RAW_HEX, q < p.length ? Ids.hex(p, q, p.length - q) : "");
            rows.add(w);
        }
        return rows;
    }

    /** Разбирает один элемент начиная с {@code q}; возвращает смещение следующего элемента. */
    private int parseItem(byte[] p, int q, Row row, ParseContext ctx) {
        if (q + 7 > p.length) throw new IllegalStateException("item header truncated");
        int bitmap = p[q] & 0xFF;
        boolean hasSession = (bitmap & 0x10) != 0;
        row.put(Columns.UE_ID, Ids.hex(p, q + 1, 2));
        row.put(Columns.UE_CTR, Ids.u16(p, q + 5));
        q += 7;
        Varint.Result sec = Varint.read(p, q);
        Varint.Result us = Varint.read(p, sec.nextOffset());
        row.put(Columns.TIME_UTC, Ids.timestamp(sec.value(), us.value()));
        q = us.nextOffset();
        if (q + 2 > p.length) throw new IllegalStateException("C-RNTI truncated");
        row.put(Columns.C_RNTI, Ids.u16(p, q));
        q += 2;
        List<String> kinds = new ArrayList<>();
        if (hasSession) {
            Varint.Result enbUe = Varint.read(p, q);
            Varint.Result mmeUe = Varint.read(p, enbUe.nextOffset());
            q = mmeUe.nextOffset();
            if (q + 6 > p.length) throw new IllegalStateException("TAI truncated");
            row.put(Columns.ENB_UE_S1AP_ID, enbUe.value()).put(Columns.MME_UE_S1AP_ID, mmeUe.value());
            row.put(Columns.TAI_PLMN, Ids.plmn(p, q)).put(Columns.TAC, Ids.u16(p, q + 3));
            row.put(Columns.SESSION_ATTR, String.format("%02x", p[q + 5] & 0xFF));
            q += 6;
            kinds.add("SESSION");
        }
        while (q < p.length) {
            int t = p[q] & 0xFF;
            if (t == 0x61 && q + 1 < p.length && p[q + 1] == 0) {
                Varint.Result ta = Varint.read(p, q + 2);
                row.put(Columns.TA, ta.value());
                row.put(Columns.DISTANCE_M, Math.round(ta.value() * ctx.taMetres()));
                kinds.add("TA");
                q = ta.nextOffset();
            } else if (q + 3 < p.length && p[q] == 0 && p[q + 1] == 0 && (p[q + 2] & 0xFF) == 0x7B) {
                int len = p[q + 3] & 0xFF;
                int bodyOff = q + 4;
                if (bodyOff + len > p.length) throw new IllegalStateException("RRC body truncated");
                decodeRrc(p, bodyOff, len, row, ctx);
                kinds.add("RRC");
                q = bodyOff + len;
            } else {
                break; // здесь начинается следующий элемент
            }
        }
        row.put(Columns.EVENT, kinds.isEmpty() ? "UE_EVENT" : "UE_" + String.join("+", kinds));
        return q;
    }

    /** Декодирует RRC-тело и раскладывает MeasurementReport по колонкам. */
    private void decodeRrc(byte[] p, int off, int len, Row row, ParseContext ctx) {
        RrcUlDcchDecoder.Message m = rrc.decode(p, off, len);
        row.put(Columns.RRC_MSG, m.name());
        if (m.measurementReport() != null) {
            var mr = m.measurementReport();
            row.put(Columns.MEAS_ID, mr.measId());
            row.put(Columns.RSRP_DBM, mr.rsrpDbm());
            row.put(Columns.RSRQ_DB, mr.rsrqDb());
            row.put(Columns.NEIGHBORS, mr.neighborsText());
            row.put(Columns.SCELLS, mr.scellsText());
            if (mr.ecidRxTxDiff() != null) row.put(Columns.ECID_RXTX, mr.ecidRxTxDiff() + "/sfn" + mr.ecidSfn());
            putGeo(row, mr.geo());
        }
        if (m.warning() != null) row.put(Columns.WARNING, "rrc: " + m.warning());
        if (m.warning() != null || m.measurementReport() == null || ctx.keepRawHex()) {
            row.put(Columns.RRC_HEX, Ids.hex(p, off, len));
        }
    }

    /** Общий для всех блоков способ положить координаты в строку. */
    static void putGeo(Row row, Geo g) {
        if (g == null) return;
        row.put(Columns.LAT, g.lat()).put(Columns.LON, g.lon()).put(Columns.ALT_M, g.altM())
           .put(Columns.LOC_UNC_M, g.uncMajorM()).put(Columns.LOC_TYPE, g.type())
           .put(Columns.SPEED_KMH, g.speedKmh()).put(Columns.BEARING_DEG, g.bearingDeg()).put(Columns.GNSS_TOD_MS, g.gnssTodMs());
    }
}
