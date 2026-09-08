package ru.nokia.lcbin.output;

import ru.nokia.lcbin.model.Columns;
import ru.nokia.lcbin.model.Row;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Пишет строки в CSV по правилам RFC 4180: UTF-8, строки разделены CRLF, значение берётся в кавычки,
 * если содержит разделитель, кавычку или перевод строки (кавычка внутри удваивается).
 * Заголовок — список колонок; по умолчанию {@link Columns#ALL}.
 */
public final class CsvWriter {
    private final char separator;

    public CsvWriter(char separator) { this.separator = separator; }

    public void write(Path target, List<Row> rows) throws IOException {
        write(target, Columns.ALL, rows);
    }

    public void write(Path target, List<String> columns, List<Row> rows) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writeLine(w, columns.toArray());
            for (Row r : rows) {
                Object[] vals = new Object[columns.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = r.get(columns.get(i));
                writeLine(w, vals);
            }
        }
    }

    private void writeLine(BufferedWriter w, Object[] vals) throws IOException {
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) w.write(separator);
            w.write(quote(vals[i]));
        }
        w.write("\r\n");
    }

    private String quote(Object v) {
        if (v == null) return "";
        String s = v.toString();
        boolean needs = s.indexOf(separator) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return needs ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
