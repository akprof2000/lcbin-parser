package ru.nokia.lcbin.rrc;

import ru.nokia.lcbin.codec.BitReader;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ручной UPER-декодер того подмножества 3GPP TS 36.331 UL-DCCH, которое встречается в трассах Nokia:
 * <ul>
 *   <li><b>MeasurementReport</b> — measId, RSRP/RSRQ обслуживающей соты, соседи LTE/UMTS/GSM/CDMA,
 *       расширения: E-CID (Rx-Tx), геокоординаты (locationInfo-r10), измерения SCell;</li>
 *   <li><b>UEInformationResponse-r9</b> — rach-Report, rlf-Report (радиосбой), logMeasReport-r10 (MDT-журнал
 *       с координатами и измерениями по времени).</li>
 * </ul>
 * Остальные типы сообщений распознаются по имени, тело остаётся в hex у вызывающего.
 *
 * <p>Почему вручную, а не генератором ASN.1: нужен небольшой кусок огромной схемы, без внешних зависимостей.
 * Правило безопасности: каждая группа расширения читается через open type (см. {@link BitReader#readOpenType()}),
 * поэтому ошибка внутри новой/неизвестной группы не ломает разбор корневых полей. Любое исключение
 * превращается в {@code warning}, наружу декодер ничего не бросает.
 *
 * <p>Как читать код: каждый метод {@code readXxx} повторяет определение ASN.1 из TS 36.331 в том же порядке
 * полей. Комментарий у метода — сама ASN.1-запись. Правила UPER: сначала бит расширения (если у SEQUENCE есть
 * «...»), потом по биту на каждое OPTIONAL-поле, потом сами поля; INTEGER (a..b) занимает столько бит,
 * сколько нужно для диапазона; SEQUENCE OF (1..n) начинается со счётчика (count−1).
 */
public final class RrcUlDcchDecoder {

    private static final String[] C1 = {
            "csfbParametersRequestCDMA2000", "measurementReport", "rrcConnectionReconfigurationComplete",
            "rrcConnectionReestablishmentComplete", "rrcConnectionSetupComplete", "securityModeComplete",
            "securityModeFailure", "ueCapabilityInformation", "ulHandoverPreparationTransfer",
            "ulInformationTransfer", "counterCheckResponse", "ueInformationResponse-r9", "proximityIndication-r9",
            "rnReconfigurationComplete-r10", "mbmsCountingResponse-r10", "interFreqRSTDMeasurementIndication-r10"};

    // ------------------------------------------------------------------ результаты

    /** Сосед любой технологии; {@link #format()} даёт компактную строку для CSV. */
    public record Neighbor(String kind, Integer freq, String pci, Double rsrpDbm, Double rsrqDb, String cgi,
                           Double rscpDbm, Double ecn0Db, Integer rssiDbm) {
        public String format() {
            StringBuilder sb = new StringBuilder();
            switch (kind) {
                case "eutra" -> {
                    sb.append("pci").append(pci);
                    if (freq != null) sb.append("/f").append(freq);
                    sb.append(':');
                    if (rsrpDbm != null) sb.append(fmt(rsrpDbm)).append("dBm");
                    if (rsrqDb != null) sb.append('/').append(fmt(rsrqDb)).append("dB");
                }
                case "utra" -> {
                    sb.append("utra").append(freq).append('/').append(pci).append(':');
                    if (rscpDbm != null) sb.append("rscp").append(fmt(rscpDbm)).append("dBm");
                    if (ecn0Db != null) sb.append("/ecn0").append(fmt(ecn0Db)).append("dB");
                }
                case "geran" -> sb.append("gsm").append(freq).append('/').append(pci).append(":rssi").append(rssiDbm).append("dBm");
                default -> sb.append(kind).append(pci).append(':').append(rsrpDbm == null ? "" : fmt(rsrpDbm));
            }
            if (cgi != null) sb.append('@').append(cgi);
            return sb.toString();
        }
    }

    /** Измерение дополнительной несущей (carrier aggregation). */
    public record SCell(int servFreqId, Double rsrpDbm, Double rsrqDb, Integer bestNeighPci, Double bestNeighRsrpDbm) {
        public String format() {
            StringBuilder sb = new StringBuilder("sf").append(servFreqId).append(':');
            if (rsrpDbm != null) sb.append(fmt(rsrpDbm)).append("dBm");
            if (rsrqDb != null) sb.append('/').append(fmt(rsrqDb)).append("dB");
            if (bestNeighPci != null) sb.append("/best pci").append(bestNeighPci).append(bestNeighRsrpDbm == null ? "" : ":" + fmt(bestNeighRsrpDbm) + "dBm");
            return sb.toString();
        }
    }

    public record MeasurementReport(int measId, int rsrp, int rsrq, List<Neighbor> neighbors, List<SCell> scells,
                                    Integer ecidRxTxDiff, Integer ecidSfn, Geo geo) {
        public double rsrpDbm() { return rsrp - 140; }
        public double rsrqDb() { return rsrq / 2.0 - 19.5; }
        public String neighborsText() { return join(neighbors); }
        public String scellsText() { return scells.isEmpty() ? null : scells.stream().map(SCell::format).collect(Collectors.joining(";")); }
    }

    public record RachReport(int preamblesSent, boolean contentionDetected) {}

    /** Одна запись MDT-журнала: где был телефон и что он видел в момент relTimeS от absTime. */
    public record LogEntry(Geo geo, int relTimeS, String servCell, int rsrp, int rsrq, List<Neighbor> neighbors) {
        public double rsrpDbm() { return rsrp - 140; }
        public double rsrqDb() { return rsrq / 2.0 - 19.5; }
        public String neighborsText() { return join(neighbors); }
    }

    /** MDT-журнал (logMeasReport-r10). absTime — строка {@code yyyy-MM-dd HH:mm:ss} из BCD-метки UE. */
    public record LogMeasReport(String absTime, String traceRef, int traceSessionRef, int tceId, List<LogEntry> entries) {}

    /** Отчёт о радиосбое (rlf-Report). Поля собраны в текст, чтобы не плодить колонки под 6 записей. */
    public record RlfReport(String details, Geo geo, Double lastRsrpDbm, Double lastRsrqDb, List<Neighbor> neighbors) {}

    /** Итог декодирования одного сообщения. {@code warning} не null, если разобрано частично. */
    public record Message(String name, MeasurementReport measurementReport, RachReport rachReport, RlfReport rlfReport,
                          LogMeasReport logMeasReport, String warning) {
        static Message of(String name) { return new Message(name, null, null, null, null, null); }
        static Message warn(String name, String w) { return new Message(name, null, null, null, null, w); }
    }

    // ------------------------------------------------------------------ вход

    public Message decode(byte[] body) {
        return decode(body, 0, body.length);
    }

    public Message decode(byte[] data, int off, int len) {
        BitReader r;
        try {
            r = new BitReader(data, off, len);
        } catch (RuntimeException e) {
            return Message.warn("undecodable", e.getMessage());
        }
        String name = "undecodable";
        try {
            // UL-DCCH-Message ::= SEQUENCE { message CHOICE { c1 CHOICE {16 вариантов}, messageClassExtension } }
            if (r.readBit() != 0) return Message.of("messageClassExtension");
            int idx = (int) r.readBits(4);
            name = C1[idx];
            return switch (idx) {
                case 1 -> readMeasurementReport(r, name);
                case 11 -> readUeInformationResponse(r, name);
                default -> Message.of(name);
            };
        } catch (RuntimeException e) {
            return Message.warn(name, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------ MeasurementReport

    /**
     * MeasurementReport ::= SEQUENCE { criticalExtensions CHOICE { c1 CHOICE { measurementReport-r8, spare7..spare1 },
     * criticalExtensionsFuture } };  MeasurementReport-r8-IEs ::= SEQUENCE { measResults MeasResults, nonCriticalExtension OPTIONAL }
     */
    private Message readMeasurementReport(BitReader r, String name) {
        if (r.readBit() != 0) return Message.warn(name, "criticalExtensionsFuture");
        int c1 = (int) r.readBits(3);
        if (c1 != 0) return Message.warn(name, "spare critical extension " + c1);
        r.readBoolean();                                   // nonCriticalExtension (пустой SEQUENCE, нечего читать)
        String[] warn = new String[1];
        MeasurementReport mr = readMeasResults(r, warn);
        return new Message(name, mr, null, null, null, warn[0]);
    }

    /**
     * MeasResults ::= SEQUENCE { measId (1..32), measResultPCell { rsrp (0..97), rsrq (0..34) },
     *   measResultNeighCells CHOICE { EUTRA, UTRA, GERAN, CDMA2000, ... } OPTIONAL, ...,
     *   [[ measResultForECID-r9 OPTIONAL ]],
     *   [[ locationInfo-r10 OPTIONAL, measResultServFreqList-r10 OPTIONAL ]],
     *   [[ measId-v1250 OPTIONAL, measResultPCell-v1250 OPTIONAL, measResultCSI-RS-List-r12 OPTIONAL ]], ... }
     */
    private MeasurementReport readMeasResults(BitReader r, String[] warn) {
        boolean ext = r.readBoolean();
        boolean neighPresent = r.readBoolean();
        int measId = (int) r.readConstrainedInt(1, 32);
        int rsrp = (int) r.readConstrainedInt(0, 97);
        int rsrq = (int) r.readConstrainedInt(0, 34);
        List<Neighbor> neighbors = new ArrayList<>();
        if (neighPresent) {
            if (r.readBoolean()) {
                warn[0] = "measResultNeighCells: extension choice";
                r.readOpenType();
            } else {
                switch ((int) r.readBits(2)) {
                    case 0 -> readEutraList(r, null, neighbors);
                    case 1 -> readUtraList(r, null, neighbors);
                    case 2 -> readGeranList(r, neighbors);
                    default -> readCdmaResults(r, neighbors);
                }
            }
        }
        List<SCell> scells = new ArrayList<>();
        Integer rxtx = null, sfn = null;
        Geo geo = null;
        if (ext) {
            boolean[] groups = r.readExtensionBitmap();
            for (int g = 0; g < groups.length; g++) {
                if (!groups[g]) continue;
                BitReader o = r.readOpenType();
                try {
                    if (g == 0) {                                   // [[ measResultForECID-r9 OPTIONAL ]]
                        if (o.readBoolean()) {
                            rxtx = (int) o.readConstrainedInt(0, 4095);
                            sfn = (int) o.readBits(10);
                        }
                    } else if (g == 1) {                            // [[ locationInfo-r10, measResultServFreqList-r10 ]]
                        boolean loc = o.readBoolean();
                        boolean sfl = o.readBoolean();
                        if (loc) geo = Geo.readLocationInfo(o);
                        if (sfl) readServFreqList(o, scells);
                    }
                    // группы 2+ (v1250, r13 ...) пока не интерпретируем: они уже вырезаны как open type
                } catch (RuntimeException e) {
                    warn[0] = "measResults extension group " + g + ": " + e.getMessage();
                }
            }
        }
        return new MeasurementReport(measId, rsrp, rsrq, neighbors, scells, rxtx, sfn, geo);
    }

    /**
     * MeasResultServFreqList-r10 ::= SEQUENCE (SIZE (1..maxServCell-r10)) OF MeasResultServFreq-r10 ::= SEQUENCE {
     *   servFreqId-r10 (0..7), measResultSCell-r10 { rsrp, rsrq } OPTIONAL,
     *   measResultBestNeighCell-r10 { physCellId (0..503), rsrp, rsrq } OPTIONAL, ... }
     * maxServCell-r10 = 5 (проверено по данным: счётчик занимает 3 бита).
     */
    private void readServFreqList(BitReader r, List<SCell> out) {
        int n = (int) r.readConstrainedInt(1, 5);
        for (int i = 0; i < n; i++) {
            boolean ext = r.readBoolean();
            boolean scell = r.readBoolean();
            boolean best = r.readBoolean();
            int id = (int) r.readConstrainedInt(0, 7);
            Double rsrp = null, rsrq = null, bestRsrp = null;
            Integer bestPci = null;
            if (scell) {
                rsrp = r.readConstrainedInt(0, 97) - 140.0;
                rsrq = r.readConstrainedInt(0, 34) / 2.0 - 19.5;
            }
            if (best) {
                bestPci = (int) r.readConstrainedInt(0, 503);
                bestRsrp = r.readConstrainedInt(0, 97) - 140.0;
                r.readConstrainedInt(0, 34);
            }
            if (ext) r.skipExtensionAdditions();
            out.add(new SCell(id, rsrp, rsrq, bestPci, bestRsrp));
        }
    }

    // ------------------------------------------------------------------ списки соседей

    /**
     * MeasResultListEUTRA ::= SEQUENCE (1..8) OF MeasResultEUTRA ::= SEQUENCE { physCellId (0..503), cgi-Info OPTIONAL,
     *   measResult SEQUENCE { rsrpResult OPTIONAL, rsrqResult OPTIONAL, ... } }
     */
    private void readEutraList(BitReader r, Integer freq, List<Neighbor> out) {
        int count = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < count; i++) {
            boolean cgiPresent = r.readBoolean();
            int pci = (int) r.readConstrainedInt(0, 503);
            String cgi = null;
            if (cgiPresent) {
                // cgi-Info ::= SEQUENCE { cellGlobalId CellGlobalIdEUTRA, trackingAreaCode BIT STRING(16), plmn-IdentityList OPTIONAL }
                boolean plmnList = r.readBoolean();
                String cell = readCellGlobalIdEutra(r);
                long tac = r.readBits(16);
                if (plmnList) skipPlmnList(r);
                cgi = cell + "/tac" + tac;
            }
            boolean ext = r.readBoolean();
            boolean rsrpP = r.readBoolean();
            boolean rsrqP = r.readBoolean();
            Double rsrp = rsrpP ? r.readConstrainedInt(0, 97) - 140.0 : null;
            Double rsrq = rsrqP ? r.readConstrainedInt(0, 34) / 2.0 - 19.5 : null;
            if (ext) r.skipExtensionAdditions();
            out.add(new Neighbor("eutra", freq, String.valueOf(pci), rsrp, rsrq, cgi, null, null, null));
        }
    }

    /**
     * MeasResultListUTRA ::= SEQUENCE (1..8) OF MeasResultUTRA ::= SEQUENCE { physCellId CHOICE { fdd (0..511), tdd (0..127) },
     *   cgi-Info SEQUENCE { cellGlobalId CellGlobalIdUTRA, locationAreaCode BIT16 OPT, routingAreaCode BIT8 OPT, plmn-IdentityList OPT } OPTIONAL,
     *   measResult SEQUENCE { utra-RSCP (-5..91) OPT, utra-EcN0 (0..49) OPT, ... } }
     * RSCP дБм = значение − 115; EcN0 дБ = значение/2 − 24.5 (TS 36.133).
     */
    private void readUtraList(BitReader r, Integer freq, List<Neighbor> out) {
        int count = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < count; i++) {
            boolean cgiPresent = r.readBoolean();
            String pci = r.readBit() == 0 ? "fdd" + r.readConstrainedInt(0, 511) : "tdd" + r.readConstrainedInt(0, 127);
            String cgi = null;
            if (cgiPresent) {
                boolean lac = r.readBoolean();
                boolean rac = r.readBoolean();
                boolean plmnList = r.readBoolean();
                String plmn = readPlmn(r);
                long cell = r.readBits(28);
                cgi = plmn + "/" + cell;
                if (lac) cgi += "/lac" + r.readBits(16);
                if (rac) r.readBits(8);
                if (plmnList) skipPlmnList(r);
            }
            boolean ext = r.readBoolean();
            boolean rscpP = r.readBoolean();
            boolean ecn0P = r.readBoolean();
            Double rscp = rscpP ? r.readConstrainedInt(-5, 91) - 115.0 : null;
            Double ecn0 = ecn0P ? r.readConstrainedInt(0, 49) / 2.0 - 24.5 : null;
            if (ext) r.skipExtensionAdditions();
            out.add(new Neighbor("utra", freq, pci, null, null, cgi, rscp, ecn0, null));
        }
    }

    /**
     * MeasResultListGERAN ::= SEQUENCE (1..8) OF MeasResultGERAN ::= SEQUENCE {
     *   carrierFreq { arfcn (0..1023), bandIndicator ENUM { dcs1800, pcs1900 } },
     *   physCellId { networkColourCode BIT3, baseStationColourCode BIT3 },
     *   cgi-Info SEQUENCE { cellGlobalId { plmn, lac BIT16, ci BIT16 }, routingAreaCode BIT8 OPT } OPTIONAL,
     *   measResult SEQUENCE { rssi (0..63), ... } }
     * RSSI дБм = значение − 110.
     */
    private void readGeranList(BitReader r, List<Neighbor> out) {
        int count = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < count; i++) {
            boolean cgiPresent = r.readBoolean();
            int arfcn = (int) r.readConstrainedInt(0, 1023);
            String band = r.readBit() == 0 ? "dcs1800" : "pcs1900";
            long ncc = r.readBits(3);
            long bcc = r.readBits(3);
            String cgi = null;
            if (cgiPresent) {
                boolean rac = r.readBoolean();
                String plmn = readPlmn(r);
                long lac = r.readBits(16);
                long ci = r.readBits(16);
                if (rac) r.readBits(8);
                cgi = plmn + "/lac" + lac + "/ci" + ci;
            }
            boolean ext = r.readBoolean();
            int rssi = (int) r.readConstrainedInt(0, 63) - 110;
            if (ext) r.skipExtensionAdditions();
            out.add(new Neighbor("geran", arfcn, band + "/ncc" + ncc + "bcc" + bcc, null, null, cgi, null, null, rssi));
        }
    }

    /**
     * MeasResultsCDMA2000 ::= SEQUENCE { preRegistrationStatusHRPD BOOLEAN, measResultListCDMA2000 (1..8) OF SEQUENCE {
     *   physCellId (0..511), cgi-Info CHOICE { 1XRTT BIT47, HRPD BIT128 } OPTIONAL,
     *   measResult SEQUENCE { pilotPnPhase (0..32767) OPT, pilotStrength (0..63), ... } } }
     */
    private void readCdmaResults(BitReader r, List<Neighbor> out) {
        r.readBoolean();
        int count = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < count; i++) {
            boolean cgiPresent = r.readBoolean();
            int pci = (int) r.readConstrainedInt(0, 511);
            if (cgiPresent) {
                if (r.readBit() == 0) r.readBits(47);          // cellGlobalId1XRTT BIT STRING(47)
                else { r.readBits(64); r.readBits(64); }       // cellGlobalIdHRPD BIT STRING(128)
            }
            boolean ext = r.readBoolean();
            boolean pn = r.readBoolean();
            if (pn) r.readConstrainedInt(0, 32767);
            int strength = (int) r.readConstrainedInt(0, 63);
            if (ext) r.skipExtensionAdditions();
            out.add(new Neighbor("cdma", null, String.valueOf(pci), (double) strength, null, null, null, null, null));
        }
    }

    /** MeasResultList2EUTRA-r9 ::= (1..8) OF { carrierFreq-r9 (0..65535), measResultList-r9 MeasResultListEUTRA } */
    private void readList2Eutra(BitReader r, List<Neighbor> out) {
        int n = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < n; i++) {
            int freq = (int) r.readConstrainedInt(0, 65535);
            readEutraList(r, freq, out);
        }
    }

    /** MeasResultList2UTRA-r9 ::= (1..8) OF { carrierFreq-r9 (0..16383), measResultList-r9 MeasResultListUTRA } */
    private void readList2Utra(BitReader r, List<Neighbor> out) {
        int n = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < n; i++) {
            int freq = (int) r.readConstrainedInt(0, 16383);
            readUtraList(r, freq, out);
        }
    }

    /** MeasResultList2GERAN-r9/-r10 ::= (1..3) OF MeasResultListGERAN */
    private void readList2Geran(BitReader r, List<Neighbor> out) {
        int n = (int) r.readConstrainedInt(1, 3);
        for (int i = 0; i < n; i++) readGeranList(r, out);
    }

    /** MeasResultList2CDMA2000-r9 ::= (1..8) OF { carrierFreq-r9 { bandClass ENUM(32,...), arfcn (0..2047) }, measResultList-r9 MeasResultsCDMA2000 } */
    private void readList2Cdma(BitReader r, List<Neighbor> out) {
        int n = (int) r.readConstrainedInt(1, 8);
        for (int i = 0; i < n; i++) {
            if (r.readBit() == 0) r.readBits(5); else r.readNormallySmall();
            r.readConstrainedInt(0, 2047);
            readCdmaResults(r, out);
        }
    }

    // ------------------------------------------------------------------ идентификаторы

    /** PLMN-Identity ::= SEQUENCE { mcc SEQUENCE(SIZE(3)) OF (0..9) OPTIONAL, mnc SEQUENCE(SIZE(2..3)) OF (0..9) } */
    private String readPlmn(BitReader r) {
        boolean mccPresent = r.readBoolean();
        StringBuilder sb = new StringBuilder();
        if (mccPresent) for (int i = 0; i < 3; i++) sb.append(r.readConstrainedInt(0, 9));
        sb.append('-');
        int mncLen = (int) r.readConstrainedInt(2, 3);
        for (int i = 0; i < mncLen; i++) sb.append(r.readConstrainedInt(0, 9));
        return sb.toString();
    }

    /** CellGlobalIdEUTRA ::= SEQUENCE { plmn-Identity, cellIdentity BIT STRING(28) } → "250-02/32161-22" */
    private String readCellGlobalIdEutra(BitReader r) {
        String plmn = readPlmn(r);
        long ci = r.readBits(28);
        return plmn + "/" + (ci >>> 8) + "-" + (ci & 0xFF);
    }

    /** PLMN-IdentityList2 ::= SEQUENCE (SIZE (1..5)) OF PLMN-Identity — читаем и выбрасываем. */
    private void skipPlmnList(BitReader r) {
        int n = (int) r.readConstrainedInt(1, 5);
        for (int i = 0; i < n; i++) readPlmn(r);
    }

    // ------------------------------------------------------------------ UEInformationResponse

    /**
     * UEInformationResponse-r9 ::= SEQUENCE { rrc-TransactionIdentifier (0..3), criticalExtensions CHOICE { c1 CHOICE {
     *   ueInformationResponse-r9, spare3, spare2, spare1 }, criticalExtensionsFuture } }
     * UEInformationResponse-r9-IEs ::= SEQUENCE { rach-Report-r9 OPTIONAL, rlf-Report-r9 OPTIONAL, nonCriticalExtension v930 OPTIONAL }
     * v930-IEs ::= { lateNonCriticalExtension OCTET STRING OPTIONAL, nonCriticalExtension v1020 OPTIONAL }
     * v1020-IEs ::= { logMeasReport-r10 OPTIONAL, nonCriticalExtension v1130 OPTIONAL }
     */
    private Message readUeInformationResponse(BitReader r, String name) {
        r.readBits(2);
        if (r.readBit() != 0) return Message.warn(name, "criticalExtensionsFuture");
        int c1 = (int) r.readBits(2);
        if (c1 != 0) return Message.warn(name, "spare critical extension " + c1);
        boolean rachPresent = r.readBoolean();
        boolean rlfPresent = r.readBoolean();
        boolean nonCrit = r.readBoolean();
        RachReport rach = null;
        RlfReport rlf = null;
        LogMeasReport log = null;
        String warn = null;
        if (rachPresent) {
            // rach-Report-r9 ::= SEQUENCE { numberOfPreamblesSent-r9 (1..200), contentionDetected-r9 BOOLEAN }
            rach = new RachReport((int) r.readConstrainedInt(1, 200), r.readBoolean());
        }
        try {
            if (rlfPresent) rlf = readRlfReport(r);
            if (nonCrit) {
                boolean late = r.readBoolean();
                boolean v1020 = r.readBoolean();
                if (late) r.readOctetString();
                if (v1020) {
                    boolean logPresent = r.readBoolean();
                    r.readBoolean();                             // nonCriticalExtension v1130 — дальше не идём
                    if (logPresent) log = readLogMeasReport(r);
                }
            }
        } catch (RuntimeException e) {
            warn = "ueInformationResponse: " + e.getMessage();
        }
        return new Message(name, null, rach, rlf, log, warn);
    }

    /**
     * RLF-Report-r9 ::= SEQUENCE { measResultLastServCell-r9 { rsrp, rsrq OPTIONAL },
     *   measResultNeighCells-r9 { EUTRA List2 OPT, UTRA List2 OPT, GERAN List2 OPT, CDMA List2 OPT } OPTIONAL, ...,
     *   [[ locationInfo-r10 OPT, failedPCellId-r10 CHOICE { cellGlobalId, pci-arfcn { pci, arfcn(0..65535) } } OPT,
     *      reestablishmentCellId-r10 OPT, timeConnFailure-r10 (0..1023) OPT, connectionFailureType-r10 ENUM{rlf,hof} OPT, previousPCellId-r10 OPT ]],
     *   [[ failedPCellId-v1090 { carrierFreq-v1090 (65536..262143) } OPT ]],
     *   [[ basicFields-r11 { c-RNTI BIT16, rlf-Cause ENUM{t310-Expiry, randomAccessProblem, rlc-MaxNumRetx, t312-Expiry-r12}, timeSinceFailure (0..172800) } OPT,
     *      previousUTRA-CellId-r11 OPT, selectedUTRA-CellId-r11 OPT ]],
     *   [[ failedPCellId-v1250 { tac-FailedPCell-r12 BIT16 } OPT, measResultLastServCell-v1250 OPT, ... ]], ... }
     */
    private RlfReport readRlfReport(BitReader r) {
        boolean ext = r.readBoolean();
        boolean neighPresent = r.readBoolean();
        boolean rsrqP = r.readBoolean();
        double rsrp = r.readConstrainedInt(0, 97) - 140.0;
        Double rsrq = rsrqP ? r.readConstrainedInt(0, 34) / 2.0 - 19.5 : null;
        List<Neighbor> neighbors = new ArrayList<>();
        if (neighPresent) {
            boolean e = r.readBoolean(), u = r.readBoolean(), g = r.readBoolean(), c = r.readBoolean();
            if (e) readList2Eutra(r, neighbors);
            if (u) readList2Utra(r, neighbors);
            if (g) readList2Geran(r, neighbors);
            if (c) readList2Cdma(r, neighbors);
        }
        StringBuilder d = new StringBuilder();
        Geo geo = null;
        if (ext) {
            boolean[] groups = r.readExtensionBitmap();
            for (int gi = 0; gi < groups.length; gi++) {
                if (!groups[gi]) continue;
                BitReader o = r.readOpenType();
                try {
                    if (gi == 0) {
                        boolean loc = o.readBoolean(), failed = o.readBoolean(), reest = o.readBoolean(),
                                time = o.readBoolean(), type = o.readBoolean(), prev = o.readBoolean();
                        if (loc) geo = Geo.readLocationInfo(o);
                        if (failed) {
                            d.append("failedPCell=");
                            if (o.readBit() == 0) d.append(readCellGlobalIdEutra(o));
                            else d.append("pci").append(o.readConstrainedInt(0, 503)).append("/f").append(o.readConstrainedInt(0, 65535));
                            d.append(';');
                        }
                        if (reest) d.append("reestablishmentCell=").append(readCellGlobalIdEutra(o)).append(';');
                        if (time) d.append("timeConnFailure=").append(o.readConstrainedInt(0, 1023) * 100).append("ms;");
                        if (type) d.append("type=").append(o.readBit() == 0 ? "rlf" : "hof").append(';');
                        if (prev) d.append("previousPCell=").append(readCellGlobalIdEutra(o)).append(';');
                    } else if (gi == 2) {
                        boolean basic = o.readBoolean();
                        o.readBoolean();
                        o.readBoolean();
                        if (basic) {
                            long crnti = o.readBits(16);
                            String[] causes = {"t310-Expiry", "randomAccessProblem", "rlc-MaxNumRetx", "t312-Expiry-r12"};
                            int cause = (int) o.readBits(2);
                            long since = o.readConstrainedInt(0, 172800);
                            d.append("c-rnti=").append(crnti).append(";cause=").append(causes[cause]).append(";timeSinceFailure=").append(since).append("s;");
                        }
                    } else if (gi == 3) {
                        boolean tac = o.readBoolean();
                        if (tac) d.append("failedTAC=").append(o.readBits(16)).append(';');
                    }
                } catch (RuntimeException e) {
                    d.append("group").append(gi).append(":").append(e.getMessage()).append(';');
                }
            }
        }
        return new RlfReport(d.toString(), geo, rsrp, rsrq, neighbors);
    }

    /**
     * LogMeasReport-r10 ::= SEQUENCE { absoluteTimeStamp-r10 BIT STRING(48) (BCD YYMMDDHHMMSS),
     *   traceReference-r10 { plmn-Identity-r10, traceId-r10 OCTET STRING(3) }, traceRecordingSessionRef-r10 OCTET STRING(2),
     *   tce-Id-r10 OCTET STRING(1), logMeasInfoList-r10 SEQUENCE (1..520) OF LogMeasInfo-r10, logMeasAvailable-r10 ENUM{true} OPTIONAL, ... }
     */
    private LogMeasReport readLogMeasReport(BitReader r) {
        boolean ext = r.readBoolean();
        r.readBoolean();                                    // logMeasAvailable
        long ts = r.readBits(48);
        String abs = bcdTime(ts);
        String plmn = readPlmn(r);
        byte[] traceId = r.readOctets(3);
        byte[] sess = r.readOctets(2);
        int tce = r.readOctets(1)[0] & 0xFF;
        int n = (int) r.readConstrainedInt(1, 520);
        List<LogEntry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) entries.add(readLogMeasInfo(r));
        if (ext) r.skipExtensionAdditions();
        String traceRef = plmn + "/" + String.format("%02x%02x%02x", traceId[0], traceId[1], traceId[2]);
        return new LogMeasReport(abs, traceRef, ((sess[0] & 0xFF) << 8) | (sess[1] & 0xFF), tce, entries);
    }

    /**
     * LogMeasInfo-r10 ::= SEQUENCE { locationInfo-r10 OPTIONAL, relativeTimeStamp-r10 (0..7200), servCellIdentity-r10 CellGlobalIdEUTRA,
     *   measResultServCell-r10 { rsrp, rsrq }, measResultNeighCells-r10 { EUTRA List2 OPT, UTRA List2 OPT, GERAN List2-r10 OPT, CDMA OPT } OPTIONAL, ... }
     */
    private LogEntry readLogMeasInfo(BitReader r) {
        boolean ext = r.readBoolean();
        boolean loc = r.readBoolean();
        boolean neigh = r.readBoolean();
        Geo geo = loc ? Geo.readLocationInfo(r) : null;
        int rel = (int) r.readConstrainedInt(0, 7200);
        String cell = readCellGlobalIdEutra(r);
        int rsrp = (int) r.readConstrainedInt(0, 97);
        int rsrq = (int) r.readConstrainedInt(0, 34);
        List<Neighbor> neighbors = new ArrayList<>();
        if (neigh) {
            boolean e = r.readBoolean(), u = r.readBoolean(), g = r.readBoolean(), c = r.readBoolean();
            if (e) readList2Eutra(r, neighbors);
            if (u) readList2Utra(r, neighbors);
            if (g) readList2Geran(r, neighbors);
            if (c) readList2Cdma(r, neighbors);
        }
        if (ext) r.skipExtensionAdditions();
        return new LogEntry(geo, rel, cell, rsrp, rsrq, neighbors);
    }

    /** 48 бит BCD → "20YY-MM-DD HH:MM:SS". Формат по TS 36.331 AbsoluteTimeInfo: год, месяц, день, час, минута, секунда. */
    static String bcdTime(long bits) {
        int[] d = new int[12];
        for (int i = 11; i >= 0; i--) { d[i] = (int) (bits & 0xF); bits >>>= 4; }
        return String.format("20%d%d-%d%d-%d%d %d%d:%d%d:%d%d", d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11]);
    }

    private static String join(List<Neighbor> list) {
        return list.isEmpty() ? null : list.stream().map(Neighbor::format).collect(Collectors.joining(";"));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
