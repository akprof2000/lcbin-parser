package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;
import ru.nokia.lcbin.rrc.RrcUlDcchDecoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Блок для записи 0x18 (маркер 0A0B0C0D).
 *
 * <p>Короткий вариант (24 байта) — служебный тик перед heartbeat:
 * <pre>  18 01 0F 0D 00  PLMN(3)  eNB(u32)  00 00  01  00  recordSeq(2)  const(2)  00 00 00 00 </pre>
 *
 * <p>Длинный вариант (байт 14 = 0x11) — UEInformationResponse от абонента с отчётами RACH / RLF / MDT:
 * <pre>
 *   @24 TraceRef(6)  @30 u32 0  @34 u32 len  @38 sec(u32)  @42 usec(u32)  @46 traceSessionRef(2)
 *   @48 01 84 04 06 01 16  @54 ECI(u32)  @58 eNB-UE-S1AP-ID(u32)  @62 C-RNTI(u32)  @66 RRC UL-DCCH (UPER)
 * </pre>
 * Из одной такой записи получается: строка UE_INFORMATION (сводка), по строке RLF_REPORT на отчёт о сбое
 * и по строке MDT_LOG на каждую запись MDT-журнала (с координатами и измерениями).
 */
public final class StatusParser implements RecordParser {
    private static final DateTimeFormatter MDT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RrcUlDcchDecoder rrc = new RrcUlDcchDecoder();

    @Override public String name() { return "status"; }
    @Override public boolean supports(int t) { return t == LcbinRecord.TYPE_STATUS; }

    @Override
    public List<Row> parse(LcbinRecord rec, ParseContext ctx) {
        byte[] p = rec.payload();
        Row row = new Row(rec, 0, "STATUS");
        List<Row> rows = new ArrayList<>();
        rows.add(row);
        try {
            if (p.length < 20) {
                return List.of(row.put(Columns.WARNING, "short status record").put(Columns.RAW_HEX, Ids.hex(p)));
            }
            row.put(Columns.PLMN, Ids.plmn(p, 5));
            row.put(Columns.ENB_ID, Ids.u32(p, 8));
            row.put(Columns.RECORD_SEQ, Ids.u16(p, 16));
            if (p.length <= 66 || (p[14] & 0xFF) != 0x11) {
                if (p.length > 24) row.put(Columns.RAW_HEX, Ids.hex(p, 24, p.length - 24));
                return rows;
            }
            row.put(Columns.EVENT, "UE_INFORMATION");
            row.put(Columns.TRACE_REF, Ids.hex(p, 24, 6));
            long sec = Ids.u32(p, 38) & 0xFFFFFFFFL;
            long us = Ids.u32(p, 42) & 0xFFFFFFFFL;
            row.put(Columns.TIME_UTC, Ids.timestamp(sec, us));
            row.put(Columns.TRACE_SESSION_REF, Ids.u16(p, 46));
            int eci = Ids.u32(p, 54);
            row.put(Columns.ECI, eci).put(Columns.CELL_ID, Ids.eciCell(eci));
            row.put(Columns.ENB_UE_S1AP_ID, Ids.u32(p, 58) & 0xFFFFFFFFL);
            row.put(Columns.C_RNTI, Ids.u32(p, 62));

            int rrcOff = 66;
            RrcUlDcchDecoder.Message m = rrc.decode(p, rrcOff, p.length - rrcOff);
            row.put(Columns.RRC_MSG, m.name());
            if (m.rachReport() != null) {
                row.put(Columns.RACH_PREAMBLES, m.rachReport().preamblesSent());
                row.put(Columns.RACH_CONTENTION, m.rachReport().contentionDetected());
            }
            if (m.warning() != null) row.put(Columns.WARNING, "rrc: " + m.warning());
            if (m.warning() != null || ctx.keepRawHex()) row.put(Columns.RRC_HEX, Ids.hex(p, rrcOff, p.length - rrcOff));

            int sub = 1;
            if (m.rlfReport() != null) {
                var rlf = m.rlfReport();
                Row r = new Row(rec, sub++, "RLF_REPORT").copyFrom(row);
                r.put(Columns.RLF_DETAILS, rlf.details()).put(Columns.RSRP_DBM, rlf.lastRsrpDbm()).put(Columns.RSRQ_DB, rlf.lastRsrqDb());
                r.put(Columns.NEIGHBORS, rlf.neighbors().isEmpty() ? null
                        : rlf.neighbors().stream().map(RrcUlDcchDecoder.Neighbor::format).reduce((a, b) -> a + ";" + b).orElse(null));
                UeEventsParser.putGeo(r, rlf.geo());
                rows.add(r);
            }
            if (m.logMeasReport() != null) {
                var log = m.logMeasReport();
                row.put(Columns.MDT_ABS_TIME, log.absTime());
                row.put(Columns.N_MSGS, log.entries().size());
                LocalDateTime abs = parseAbs(log.absTime());
                for (RrcUlDcchDecoder.LogEntry e : log.entries()) {
                    Row r = new Row(rec, sub++, "MDT_LOG").copyFrom(row);
                    r.put(Columns.MDT_ABS_TIME, log.absTime()).put(Columns.MDT_REL_TIME_S, e.relTimeS());
                    if (abs != null) r.put(Columns.TIME_UTC, Ids.TS_FMT.format(abs.plusSeconds(e.relTimeS()).toInstant(ZoneOffset.UTC)));
                    r.put(Columns.MDT_SERV_CELL, e.servCell());
                    r.put(Columns.RSRP_DBM, e.rsrpDbm()).put(Columns.RSRQ_DB, e.rsrqDb());
                    r.put(Columns.NEIGHBORS, e.neighborsText());
                    UeEventsParser.putGeo(r, e.geo());
                    rows.add(r);
                }
            }
        } catch (RuntimeException e) {
            row.put(Columns.WARNING, e.getMessage()).put(Columns.RAW_HEX, Ids.hex(p));
        }
        return rows;
    }

    private static LocalDateTime parseAbs(String s) {
        try {
            return LocalDateTime.parse(s, MDT_FMT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
