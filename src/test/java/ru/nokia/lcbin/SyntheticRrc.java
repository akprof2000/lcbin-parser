package ru.nokia.lcbin;

import java.util.List;

/**
 * Сборка синтетических RRC UL-DCCH сообщений (TS 36.331, UPER) с заранее известными значениями.
 * Все идентификаторы вымышленные (PLMN 001-01, eNB 12345 и т.п.).
 */
final class SyntheticRrc {
    private SyntheticRrc() {}

    /** Сосед LTE для MeasurementReport. */
    record Eutra(int pci, int rsrpIdx, int rsrqIdx) {}
    /** Точка TS 36.355 EllipsoidPointWithAltitude (8 байт). */
    record Point(double lat, double lon, int altM) {
        byte[] encode() {
            UperWriter w = new UperWriter();
            w.bit(lat < 0 ? 1 : 0).bits(Math.round(Math.abs(lat) * (1 << 23) / 90.0), 23);
            w.bits(Math.round(lon * (1 << 24) / 360.0) + (1 << 23), 24);
            w.bit(altM < 0 ? 1 : 0).bits(Math.abs(altM), 15);
            return w.toBytes();
        }
    }

    /** PLMN-Identity ::= { mcc SEQUENCE(3) OF digit OPTIONAL, mnc SEQUENCE(2..3) OF digit } — "001-01". */
    static void plmn(UperWriter w) {
        w.bit(1);                                   // mcc present
        w.cint(0, 0, 9).cint(0, 0, 9).cint(1, 0, 9);
        w.cint(2, 2, 3);                            // 2 цифры MNC
        w.cint(0, 0, 9).cint(1, 0, 9);
    }

    /** CellGlobalIdEUTRA ::= { plmn, cellIdentity BIT STRING(28) } */
    static void cellGlobalId(UperWriter w, int enb, int cell) {
        plmn(w);
        w.bits(((long) enb << 8) | cell, 28);
    }

    /** MeasResultListEUTRA: count(1..8), элементы без cgi-Info и без расширений. */
    static void eutraList(UperWriter w, List<Eutra> nb) {
        w.cint(nb.size(), 1, 8);
        for (Eutra e : nb) {
            w.bit(0);                               // cgi-Info absent
            w.cint(e.pci(), 0, 503);
            w.bit(0).bit(1).bit(1);                 // ext=0, rsrp present, rsrq present
            w.cint(e.rsrpIdx(), 0, 97).cint(e.rsrqIdx(), 0, 34);
        }
    }

    /**
     * LocationInfo-r10 с ellipsoidPointWithAltitude-r10 (корневой вариант CHOICE), опционально скорость и GNSS TOD.
     */
    static void locationInfo(UperWriter w, Point p, Integer bearing, Integer speedKmh, Long todMs) {
        w.bit(0);                                   // ext
        w.bool(speedKmh != null).bool(todMs != null);
        w.bit(0).bit(1);                            // CHOICE: root, index 1 = ellipsoidPointWithAltitude-r10
        w.octetString(p.encode());
        if (speedKmh != null) {
            UperWriter v = new UperWriter().bits(bearing, 9).bits(speedKmh, 11);
            w.octetString(v.toBytes());
        }
        if (todMs != null) w.octetString(new UperWriter().bits(todMs, 22).toBytes());
    }

    /**
     * MeasurementReport с measId, PCell, соседями и (опционально) группой расширения
     * [[ locationInfo-r10, measResultServFreqList-r10 ]].
     */
    static byte[] measurementReport(int measId, int rsrpIdx, int rsrqIdx, List<Eutra> neighbors,
                                    Point geo, Long todMs, int[][] scells) {
        boolean ext = geo != null || scells != null;
        UperWriter w = new UperWriter();
        w.bit(0).bits(1, 4);                        // UL-DCCH c1 → measurementReport
        w.bit(0).bits(0, 3);                        // criticalExtensions c1 → measurementReport-r8
        w.bit(0);                                   // nonCriticalExtension absent
        w.bool(ext).bool(!neighbors.isEmpty());
        w.cint(measId, 1, 32).cint(rsrpIdx, 0, 97).cint(rsrqIdx, 0, 34);
        if (!neighbors.isEmpty()) {
            w.bit(0).bits(0, 2);                    // CHOICE root, measResultListEUTRA
            eutraList(w, neighbors);
        }
        if (ext) {
            w.small(1);                             // 2 группы расширения
            w.bit(0).bit(1);                        // [[ECID]] absent, [[loc, servFreq]] present
            UperWriter g = new UperWriter();
            g.bool(geo != null).bool(scells != null);
            if (geo != null) locationInfo(g, geo, null, null, todMs);
            if (scells != null) {
                g.cint(scells.length, 1, 5);
                for (int[] s : scells) {            // {servFreqId, rsrpIdx, rsrqIdx}
                    g.bit(0).bit(1).bit(0);         // ext, measResultSCell present, bestNeigh absent
                    g.cint(s[0], 0, 7).cint(s[1], 0, 97).cint(s[2], 0, 34);
                }
            }
            w.openType(g);
        }
        return w.toBytes();
    }

    /** Одна запись MDT-журнала. */
    record LogEntry(Point geo, int relTimeS, int servEnb, int servCell, int rsrpIdx, int rsrqIdx,
                    List<Eutra> eutra, int eutraFreq, int[][] utra, int utraFreq, int[][] geran) {}

    /**
     * UEInformationResponse-r9: rach-Report, опционально rlf-Report (сота отказа/переустановления, причина)
     * и logMeasReport-r10 с записями.
     */
    static byte[] ueInformationResponse(Integer preambles, boolean contention, boolean rlf,
                                        String absTimeBcd, List<LogEntry> log) {
        UperWriter w = new UperWriter();
        w.bit(0).bits(11, 4);                       // c1 → ueInformationResponse-r9
        w.bits(1, 2);                               // rrc-TransactionIdentifier
        w.bit(0).bits(0, 2);                        // c1 → ueInformationResponse-r9
        w.bool(preambles != null).bool(rlf).bool(log != null);
        if (preambles != null) w.cint(preambles, 1, 200).bool(contention);
        if (rlf) rlfReport(w);
        if (log != null) {
            w.bit(0).bit(1);                        // v930: late absent, v1020 present
            w.bit(1).bit(0);                        // v1020: logMeasReport present, v1130 absent
            logMeasReport(w, absTimeBcd, log);
        }
        return w.toBytes();
    }

    /** RLF-Report-r9: последняя сота −120 dBm, один сосед LTE, группы расширения r10 и r11. */
    static void rlfReport(UperWriter w) {
        w.bit(1);                                   // ext
        w.bit(1).bit(1);                            // neigh present, rsrq present
        w.cint(20, 0, 97).cint(10, 0, 34);
        w.bit(1).bit(0).bit(0).bit(0);              // EUTRA only
        w.cint(1, 1, 8).cint(1300, 0, 65535);
        eutraList(w, List.of(new Eutra(77, 25, 9)));
        w.small(2);                                 // 3 группы
        w.bit(1).bit(0).bit(1);
        UperWriter g0 = new UperWriter();
        g0.bit(0).bit(1).bit(1).bit(1).bit(1).bit(1);  // loc absent, failed, reest, time, type, prev
        g0.bit(0); cellGlobalId(g0, 12345, 7);      // failedPCellId = cellGlobalId
        cellGlobalId(g0, 12345, 8);                 // reestablishmentCellId
        g0.cint(10, 0, 1023);                       // timeConnFailure 1000 ms
        g0.bit(1);                                  // hof
        cellGlobalId(g0, 12346, 1);                 // previousPCellId
        w.openType(g0);
        UperWriter g2 = new UperWriter();
        g2.bit(1).bit(0).bit(0);                    // basicFields present
        g2.bits(4321, 16).bits(0, 2).cint(5, 0, 172800);   // c-RNTI, t310-Expiry, 5 s
        w.openType(g2);
    }

    static void logMeasReport(UperWriter w, String absTimeBcd, List<LogEntry> log) {
        w.bit(0).bit(0);                            // ext, logMeasAvailable absent
        w.bits(Long.parseLong(absTimeBcd, 16), 48); // BCD YYMMDDHHMMSS как hex-строка
        plmn(w);
        w.octets(new byte[]{0, 0, 1});              // traceId
        w.octets(new byte[]{0, 2});                 // traceRecordingSessionRef
        w.octets(new byte[]{1});                    // tce-Id
        w.cint(log.size(), 1, 520);
        for (LogEntry e : log) {
            w.bit(0);                               // ext
            boolean neigh = !e.eutra().isEmpty() || e.utra() != null || e.geran() != null;
            w.bool(e.geo() != null).bool(neigh);
            if (e.geo() != null) locationInfo(w, e.geo(), null, null, null);
            w.cint(e.relTimeS(), 0, 7200);
            cellGlobalId(w, e.servEnb(), e.servCell());
            w.cint(e.rsrpIdx(), 0, 97).cint(e.rsrqIdx(), 0, 34);
            if (neigh) {
                w.bool(!e.eutra().isEmpty()).bool(e.utra() != null).bool(e.geran() != null).bit(0);
                if (!e.eutra().isEmpty()) {
                    w.cint(1, 1, 8).cint(e.eutraFreq(), 0, 65535);
                    eutraList(w, e.eutra());
                }
                if (e.utra() != null) {             // {pciFdd, rscpIdx, ecn0Idx}
                    w.cint(1, 1, 8).cint(e.utraFreq(), 0, 16383);
                    w.cint(e.utra().length, 1, 8);
                    for (int[] u : e.utra()) {
                        w.bit(0);                   // cgi absent
                        w.bit(0).cint(u[0], 0, 511); // fdd
                        w.bit(0).bit(1).bit(1);
                        w.cint(u[1], -5, 91).cint(u[2], 0, 49);
                    }
                }
                if (e.geran() != null) {            // {arfcn, ncc, bcc, rssiIdx}
                    w.cint(1, 1, 3);
                    w.cint(e.geran().length, 1, 8);
                    for (int[] g : e.geran()) {
                        w.bit(0);                   // cgi absent
                        w.cint(g[0], 0, 1023).bit(0);   // dcs1800
                        w.bits(g[1], 3).bits(g[2], 3);
                        w.bit(0).cint(g[3], 0, 63);
                    }
                }
            }
        }
    }
}
