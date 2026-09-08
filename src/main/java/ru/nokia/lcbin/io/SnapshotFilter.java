package ru.nokia.lcbin.io;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nokia Tracer сохраняет один и тот же TCP-поток несколько раз как {@code <префикс>.lcbin_<байт>} —
 * снимки растущего файла, где меньший является байтовым префиксом большего. Если разобрать все,
 * данные задвоятся. Стратегия выбирает, какие снимки одной группы оставить:
 * <ul>
 *   <li>ALL — все (по умолчанию: один CSV на каждый входной файл);</li>
 *   <li>LARGEST — самый полный;</li>
 *   <li>MEDIAN — средний по размеру (файлы нулевого размера не считаются).</li>
 * </ul>
 */
public final class SnapshotFilter {
    public enum Strategy { ALL, LARGEST, MEDIAN }

    public static List<SourceFile> apply(List<SourceFile> files, Strategy s) {
        if (s == Strategy.ALL) return files;
        Map<String, List<SourceFile>> groups = new LinkedHashMap<>();
        for (SourceFile f : files) groups.computeIfAbsent(groupKey(f.name()), k -> new ArrayList<>()).add(f);
        List<SourceFile> out = new ArrayList<>();
        for (List<SourceFile> g : groups.values()) {
            List<SourceFile> nonEmpty = g.stream().filter(f -> f.data().length > 0)
                    .sorted(Comparator.comparingInt(f -> f.data().length)).toList();
            if (nonEmpty.isEmpty()) continue;
            out.add(s == Strategy.LARGEST ? nonEmpty.get(nonEmpty.size() - 1) : nonEmpty.get(nonEmpty.size() / 2));
        }
        return out;
    }

    /** {@code dir/ne32161_..._eNodeB001.lcbin_128680} → {@code dir/ne32161_..._eNodeB001.lcbin}; прочие имена — своя группа. */
    static String groupKey(String name) {
        int i = name.lastIndexOf(".lcbin_");
        return i < 0 ? name : name.substring(0, i + 6);
    }
}
