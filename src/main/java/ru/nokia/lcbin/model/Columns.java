package ru.nokia.lcbin.model;

import java.util.List;

/**
 * Единая схема колонок выходного CSV. Каждый блок-парсер заполняет только «свои» колонки,
 * остальные остаются пустыми — так один файл вмещает записи всех типов и его удобно фильтровать
 * по колонке {@link #EVENT}.
 *
 * <p>Если добавляете новое поле: объявите константу, добавьте её в {@link #ALL} (порядок = порядок
 * колонок в файле) и опишите в README.
 */
public final class Columns {
    private Columns() {}

    // --- служебные: где запись лежит в файле ---
    public static final String RECORD_INDEX = "record_index";
    public static final String RECORD_OFFSET = "record_offset";
    public static final String RECORD_TYPE = "record_type";
    /** Вид события: UE_TA, UE_RRC, UE_SESSION+RRC, TRACE_SESSION_START, MDT_LOG, RLF_REPORT, ... */
    public static final String EVENT = "event";
    public static final String TIME_UTC = "time_utc";

    // --- сеть и сота ---
    public static final String PLMN = "plmn";
    public static final String ENB_ID = "enb_id";
    public static final String CELL_ID = "cell_id";
    public static final String ECI = "eci";
    public static final String TRACE_REF = "trace_ref";
    public static final String TRACE_SESSION_REF = "trace_session_ref";
    public static final String RECORD_SEQ = "record_seq";

    // --- абонент (UE) ---
    public static final String UE_ID = "ue_id";
    public static final String UE_CTR = "ue_ctr";
    public static final String C_RNTI = "c_rnti";
    public static final String ENB_UE_S1AP_ID = "enb_ue_s1ap_id";
    public static final String MME_UE_S1AP_ID = "mme_ue_s1ap_id";
    public static final String TAI_PLMN = "tai_plmn";
    public static final String TAC = "tac";
    public static final String SESSION_ATTR = "session_attr";

    // --- Timing Advance ---
    public static final String TA = "timing_advance";
    public static final String DISTANCE_M = "distance_m";

    // --- MeasurementReport ---
    public static final String MEAS_ID = "meas_id";
    public static final String RSRP_DBM = "rsrp_dbm";
    public static final String RSRQ_DB = "rsrq_db";
    /** Соседи: {@code pci280:-105dBm/-2.5dB;pci104:...} (LTE), {@code utra10687/pci349:rscp-90dBm/ecn0-7.0dB}, {@code gsm68/dcs1800/ncc1bcc3:rssi-61dBm} */
    public static final String NEIGHBORS = "neighbors";
    /** Измерения SCell (carrier aggregation): {@code sf1:-95dBm/-10.5dB;...} */
    public static final String SCELLS = "scell_results";
    /** E-CID: UE Rx-Tx time difference (0..4095) и SFN — если eNB запрашивал позиционирование. */
    public static final String ECID_RXTX = "ecid_rxtx_diff";

    // --- геолокация (locationInfo-r10 в MeasurementReport и в MDT-журнале) ---
    public static final String LAT = "lat";
    public static final String LON = "lon";
    public static final String ALT_M = "alt_m";
    /** Большая полуось эллипса неопределённости, м (если UE прислал). */
    public static final String LOC_UNC_M = "loc_uncertainty_m";
    public static final String LOC_TYPE = "loc_type";
    public static final String SPEED_KMH = "speed_kmh";
    public static final String BEARING_DEG = "bearing_deg";
    public static final String GNSS_TOD_MS = "gnss_tod_ms";

    // --- сессии и служебные записи ---
    public static final String N_MSGS = "n_msgs";
    public static final String RRC_MSG = "rrc_message";
    public static final String RACH_PREAMBLES = "rach_preambles_sent";
    public static final String RACH_CONTENTION = "rach_contention";

    // --- MDT (logMeasReport) ---
    /** Абсолютное время начала журнала из отчёта UE (BCD YYMMDDHHMMSS). */
    public static final String MDT_ABS_TIME = "mdt_abs_time";
    /** Смещение записи журнала от абсолютного времени, с. */
    public static final String MDT_REL_TIME_S = "mdt_rel_time_s";
    /** Обслуживающая сота в момент записи журнала: {@code 250-02/32161-22} */
    public static final String MDT_SERV_CELL = "mdt_serv_cell";

    // --- RLF (radio link failure) ---
    /** Текстовая сводка RLF-отчёта: причина, тип, сота отказа, сота переустановления, время. */
    public static final String RLF_DETAILS = "rlf_details";

    // --- прочее ---
    public static final String RRC_HEX = "rrc_hex";
    public static final String VERSION = "version";
    public static final String RAW_HEX = "raw_hex";
    public static final String WARNING = "warning";

    public static final List<String> ALL = List.of(
            RECORD_INDEX, RECORD_OFFSET, RECORD_TYPE, EVENT, TIME_UTC, PLMN, ENB_ID, CELL_ID, ECI,
            TRACE_REF, TRACE_SESSION_REF, RECORD_SEQ, UE_ID, UE_CTR, C_RNTI, ENB_UE_S1AP_ID, MME_UE_S1AP_ID,
            TAI_PLMN, TAC, SESSION_ATTR, TA, DISTANCE_M, MEAS_ID, RSRP_DBM, RSRQ_DB, NEIGHBORS, SCELLS, ECID_RXTX,
            LAT, LON, ALT_M, LOC_UNC_M, LOC_TYPE, SPEED_KMH, BEARING_DEG, GNSS_TOD_MS,
            N_MSGS, RRC_MSG, RACH_PREAMBLES, RACH_CONTENTION, MDT_ABS_TIME, MDT_REL_TIME_S, MDT_SERV_CELL, RLF_DETAILS,
            RRC_HEX, VERSION, RAW_HEX, WARNING);
}
