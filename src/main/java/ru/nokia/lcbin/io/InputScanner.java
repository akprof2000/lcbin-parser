package ru.nokia.lcbin.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Обходит входную папку (или один файл) и отдаёт каждый найденный поток lcbin.
 * Архивы .zip/.7z распаковываются, вложенные архивы — тоже (не глубже {@link #MAX_DEPTH}).
 * Файлы с «не нашими» расширениями (csv, txt, md, docx, ...) пропускаются, всё остальное
 * отдаётся как есть — решает уже читатель записей, поток это или нет.
 *
 * <p>Ошибка распаковки одного архива не останавливает обход: она печатается в stderr и
 * накапливается в {@link #errors()}, чтобы {@code Main} вернул ненулевой код выхода.
 */
public final class InputScanner {
    public static final int MAX_DEPTH = 4;
    private static final Set<String> SKIP_EXT = Set.of("csv", "txt", "md", "log", "json", "xml", "docx", "xlsx", "pptx", "jar", "pdf", "html");

    private final ArchiveExtractor extractor;
    private final List<String> errors = new ArrayList<>();

    public InputScanner(char[] password) {
        this.extractor = new ArchiveExtractor(password);
    }

    /** Ошибки распаковки, накопленные за время обхода (пусто = всё хорошо). */
    public List<String> errors() { return errors; }

    public List<SourceFile> scan(Path root) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        scan(root, out::add);
        return out;
    }

    public void scan(Path root, Consumer<SourceFile> sink) throws IOException {
        if (Files.isRegularFile(root)) {
            emit(root.getFileName().toString(), Files.readAllBytes(root), sink, 0);
            return;
        }
        if (!Files.isDirectory(root)) throw new IOException("input not found: " + root);
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = s.filter(Files::isRegularFile).sorted().toList();
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                emit(rel, Files.readAllBytes(f), sink, 0);
            }
        }
    }

    private void emit(String name, byte[] data, Consumer<SourceFile> sink, int depth) {
        String lower = name.toLowerCase(Locale.ROOT);
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
        boolean archiveByName = ext.equals("zip") || ext.equals("7z");
        if (archiveByName || (!SKIP_EXT.contains(ext) && ArchiveExtractor.looksLikeArchive(data))) {
            if (depth >= MAX_DEPTH) {
                errors.add(name + ": nested archives deeper than " + MAX_DEPTH + ", skipped");
                System.err.println(errors.get(errors.size() - 1));
                return;
            }
            try {
                for (ArchiveExtractor.Entry e : extractor.extract(name, data)) {
                    emit(name + "/" + e.name(), e.data(), sink, depth + 1);   // рекурсия = вложенные архивы
                }
            } catch (IOException ex) {
                errors.add(name + ": " + ex.getMessage());
                System.err.println("archive error: " + errors.get(errors.size() - 1));
            }
            return;
        }
        if (SKIP_EXT.contains(ext)) return;
        sink.accept(new SourceFile(name, SourceFile.stemOf(name), data));
    }
}
