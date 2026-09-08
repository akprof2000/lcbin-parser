package ru.nokia.lcbin.parsers;

import ru.nokia.lcbin.model.LcbinRecord;
import ru.nokia.lcbin.model.Row;

import java.util.List;

/**
 * Один блок разбора = один тип записи (или семейство типов).
 *
 * <p>Правила для блока:
 * <ul>
 *   <li>без состояния и потокобезопасен — один экземпляр вызывается из разных потоков одновременно;</li>
 *   <li>не бросать исключений наружу: проблемы кладём в колонку {@code warning} и отдаём сырые байты в {@code raw_hex}
 *       (если всё же бросит — диспетчер превратит это в строку PARSE_ERROR, файл не пострадает);</li>
 *   <li>заполнять только те колонки, которые блок понимает.</li>
 * </ul>
 * Чтобы добавить новый тип записи: реализовать интерфейс и добавить в {@link ParserRegistry#full()}.
 */
public interface RecordParser {
    /** Имя блока для логов и опции {@code --blocks}. */
    String name();

    boolean supports(int recordType);

    /** Разбирает запись в ноль или больше строк CSV. */
    List<Row> parse(LcbinRecord record, ParseContext ctx);
}
