package ru.nokia.lcbin.parsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Набор блоков разбора. Для типа записи берётся первый блок, который его {@link RecordParser#supports поддерживает};
 * всё остальное ловит {@link UnknownRecordParser} и выгружает в hex.
 *
 * <p>Для узкой задачи можно собрать реестр из одного-двух блоков — остальные записи пройдут «мимо» быстро:
 * {@code ParserRegistry.of(new UeEventsParser())} даёт только Timing Advance и MeasurementReport.
 * В CLI то же самое делает опция {@code --blocks ue-events}.
 */
public final class ParserRegistry {
    private final List<RecordParser> parsers;
    private final RecordParser fallback = new UnknownRecordParser();

    private ParserRegistry(List<RecordParser> parsers) {
        this.parsers = Collections.unmodifiableList(new ArrayList<>(parsers));
    }

    public static ParserRegistry of(RecordParser... blocks) {
        return new ParserRegistry(List.of(blocks));
    }

    /** Все известные блоки. */
    public static ParserRegistry full() {
        return of(new FileHeaderParser(), new HeartbeatParser(), new SessionParser(), new UeEventsParser(), new StatusParser());
    }

    public RecordParser resolve(int recordType) {
        for (RecordParser p : parsers) if (p.supports(recordType)) return p;
        return fallback;
    }

    public List<RecordParser> blocks() { return parsers; }
}
