package ru.nokia.lcbin.io;

import ru.nokia.lcbin.codec.Ids;
import ru.nokia.lcbin.model.LcbinRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Режет поток lcbin на записи по маркерам.
 * <pre>
 *   4B 16 B0 55  len(u32 BE, только payload)          payload
 *   0A 0B 0C 0D  len(u32 BE, ВКЛЮЧАЯ 8-байт заголовок) payload
 * </pre>
 * Читатель никогда не бросает исключений: любой мусор, неверная длина или обрезанный хвост
 * превращаются в запись типа -1 ({@code GAP}), после чего поиск продолжается со следующего маркера.
 * Так один повреждённый байт не уничтожает остаток файла.
 */
public final class LcbinRecordReader {

    public List<LcbinRecord> readAll(byte[] data) {
        List<LcbinRecord> out = new ArrayList<>();
        int pos = 0;
        int idx = 0;
        while (pos + 8 <= data.length) {
            int marker = Ids.u32(data, pos);
            long len = Ids.u32(data, pos + 4) & 0xFFFFFFFFL;   // long — чтобы 0xFFFFFFFF не стал отрицательным
            long payloadStart = pos + 8L;
            long next;
            if (marker == LcbinRecord.MARKER_MAIN) {
                next = payloadStart + len;
            } else if (marker == LcbinRecord.MARKER_STATUS) {
                next = pos + len;
            } else {
                // не маркер: ищем следующий маркер и отдаём пропуск как GAP
                pos = gap(data, pos, pos + 1, marker, idx++, out);
                if (pos < 0) return out;
                continue;
            }
            if (next <= payloadStart) {
                // нулевая/слишком маленькая длина: это не запись, ресинхронизируемся
                pos = gap(data, pos, pos + 4, marker, idx++, out);
                if (pos < 0) return out;
                continue;
            }
            if (next > data.length) {
                // длина выходит за файл: обрезанный хвост (снимок остановлен посреди записи)
                out.add(new LcbinRecord(idx, pos, marker, -1, Arrays.copyOfRange(data, pos, data.length)));
                return out;
            }
            byte[] payload = Arrays.copyOfRange(data, (int) payloadStart, (int) next);
            out.add(new LcbinRecord(idx++, pos, marker, payload[0] & 0xFF, payload));
            pos = (int) next;
        }
        if (pos < data.length) {
            out.add(new LcbinRecord(idx, pos, 0, -1, Arrays.copyOfRange(data, pos, data.length)));
        }
        return out;
    }

    /** Добавляет GAP-запись от {@code from} до следующего маркера и возвращает его позицию (или -1, если маркеров больше нет). */
    private static int gap(byte[] data, int from, int searchFrom, int marker, int idx, List<LcbinRecord> out) {
        int resync = findNextMarker(data, searchFrom);
        int gapEnd = resync < 0 ? data.length : resync;
        out.add(new LcbinRecord(idx, from, marker, -1, Arrays.copyOfRange(data, from, gapEnd)));
        return resync;
    }

    private static int findNextMarker(byte[] d, int from) {
        for (int i = Math.max(from, 0); i + 4 <= d.length; i++) {
            int m = Ids.u32(d, i);
            if (m == LcbinRecord.MARKER_MAIN || m == LcbinRecord.MARKER_STATUS) return i;
        }
        return -1;
    }
}
