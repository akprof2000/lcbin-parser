package ru.nokia.lcbin.io;

/**
 * Один логический входной поток: обычный файл на диске или элемент внутри архива.
 *
 * @param name       имя для логов; для элементов архива — {@code archive.7z/внутренний/путь}
 * @param outputStem безопасная основа имени выходного CSV (разделители путей заменены на «_»)
 * @param data       содержимое целиком
 */
public record SourceFile(String name, String outputStem, byte[] data) {

    /**
     * Делает из имени (с путями и любыми символами) основу имени файла. Если имя слишком длинное,
     * оставляем хвост и добавляем хэш всего имени, чтобы два разных длинных имени не совпали.
     * Гарантию уникальности между разными источниками даёт {@code Main} (добавляет суффикс ~2, ~3, …).
     */
    public static String stemOf(String name) {
        String s = name.replace('\\', '/');
        s = s.replaceAll("[^A-Za-z0-9._%@=+-]", "_").replace("..", "_");
        if (s.length() > 160) {
            s = s.substring(s.length() - 160) + "_" + Integer.toHexString(name.hashCode());
        }
        return s;
    }
}
