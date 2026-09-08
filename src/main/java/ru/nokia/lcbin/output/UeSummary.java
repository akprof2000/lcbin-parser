package ru.nokia.lcbin.output;

import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.Row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сводка по абонентам: одна строка на (eNB, сота, ue_id, C-RNTI) с медианой Timing Advance по ненулевым
 * отсчётам. Нули отбрасываются, потому что TA = 0 eNodeB выдаёт в момент создания нового контекста
 * (до первого RACH) — это не расстояние, а «ещё не измерено». Медиана устойчивее среднего к единичным выбросам.
 */
public final class UeSummary {
    public static final List<String> COLUMNS = List.of(
            "enb_id", "cell_id", "ue_id", "c_rnti", "enb_ue_s1ap_id", "first_time_utc", "last_time_utc",
            "samples", "samples_nonzero", "ta_median", "distance_median_m", "ta_min", "ta_max", "rsrp_median_dbm");

    private record Key(Object enb, Object cell, Object ue, Object rnti) {}

    private static final class Acc {
        String first;
        String last;
        int samples;
        final List<Long> ta = new ArrayList<>();
        final List<Double> rsrp = new ArrayList<>();
    }

    public List<Row> summarize(List<Row> rows, double taMetres) {
        Map<Key, Acc> acc = new LinkedHashMap<>();
        Map<Key, Object> lastS1ap = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.get(Columns.UE_ID) == null) continue;
            Key k = new Key(r.get(Columns.ENB_ID), r.get(Columns.CELL_ID), r.get(Columns.UE_ID), r.get(Columns.C_RNTI));
            if (r.get(Columns.ENB_UE_S1AP_ID) != null) lastS1ap.put(k, r.get(Columns.ENB_UE_S1AP_ID));
            Object rsrp = r.get(Columns.RSRP_DBM);
            Object ta = r.get(Columns.TA);
            if (ta == null && rsrp == null) continue;
            Acc a = acc.computeIfAbsent(k, x -> new Acc());
            String t = (String) r.get(Columns.TIME_UTC);
            if (a.first == null) a.first = t;
            a.last = t;
            if (ta != null) {
                a.samples++;
                long v = ((Number) ta).longValue();
                if (v != 0) a.ta.add(v);
            }
            if (rsrp != null) a.rsrp.add(((Number) rsrp).doubleValue());
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<Key, Acc> e : acc.entrySet()) {
            Key k = e.getKey();
            Acc a = e.getValue();
            if (a.samples == 0) continue;
            Row row = new Row("UE_TA");
            row.put("enb_id", k.enb).put("cell_id", k.cell).put("ue_id", k.ue).put("c_rnti", k.rnti)
               .put("enb_ue_s1ap_id", lastS1ap.get(k)).put("first_time_utc", a.first).put("last_time_utc", a.last)
               .put("samples", a.samples).put("samples_nonzero", a.ta.size());
            if (!a.ta.isEmpty()) {
                List<Long> s = new ArrayList<>(a.ta);
                s.sort(null);
                double med = median(s);
                row.put("ta_median", med).put("distance_median_m", Math.round(med * taMetres))
                   .put("ta_min", s.get(0)).put("ta_max", s.get(s.size() - 1));
            }
            if (!a.rsrp.isEmpty()) {
                List<Double> s = new ArrayList<>(a.rsrp);
                s.sort(null);
                row.put("rsrp_median_dbm", s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2);
            }
            out.add(row);
        }
        return out;
    }

    private static double median(List<Long> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
