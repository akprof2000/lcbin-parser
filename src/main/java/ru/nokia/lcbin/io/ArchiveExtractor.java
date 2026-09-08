package ru.nokia.lcbin.io;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Распаковывает архивы в память:
 * <ul>
 *   <li>.zip без пароля — стандартный {@link ZipInputStream};</li>
 *   <li>.zip с паролем — библиотека zip4j (ей нужен файл на диске, поэтому пишем временный);</li>
 *   <li>.7z — commons-compress (LZMA/LZMA2, шифрование 7zAES с паролем).</li>
 * </ul>
 * Каталоги пропускаются. Чтобы «zip-бомба» не съела всю память, размер одного распакованного
 * элемента ограничен {@link #MAX_ENTRY_BYTES}; элемент больше лимита пропускается с сообщением в stderr.
 */
public final class ArchiveExtractor {
    /** Один распакованный элемент архива. */
    public record Entry(String name, byte[] data) {}

    /** Лимит на распакованный элемент (512 МБ). Реальные трассы — единицы мегабайт. */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    private static final byte[] SIG_7Z = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private final char[] password;

    public ArchiveExtractor(char[] password) {
        this.password = password;
    }

    /** Проверка по сигнатуре: 7z начинается с {@code 7z BC AF 27 1C}, zip — с {@code PK 03 04}. */
    public static boolean looksLikeArchive(byte[] d) {
        if (d.length >= 6) {
            boolean sevenZ = true;
            for (int i = 0; i < 6; i++) if (d[i] != SIG_7Z[i]) { sevenZ = false; break; }
            if (sevenZ) return true;
        }
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    public List<Entry> extract(String name, byte[] data) throws IOException {
        if (data.length >= 6 && data[0] == '7' && data[1] == 'z') return extract7z(name, data);
        return extractZip(name, data);
    }

    private List<Entry> extract7z(String name, byte[] data) throws IOException {
        List<Entry> out = new ArrayList<>();
        SevenZFile.Builder b = new SevenZFile.Builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(data));
        if (password != null) b.setPassword(password);
        try (SevenZFile z = b.get()) {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (e.hasStream() && e.getSize() > MAX_ENTRY_BYTES) {
                    System.err.println("skip " + name + "/" + e.getName() + ": " + e.getSize() + " bytes exceeds limit");
                    continue;
                }
                byte[] buf = readLimited(z.getInputStream(e), name + "/" + e.getName());
                out.add(new Entry(e.getName().replace('\\', '/'), buf));
            }
        } catch (PasswordRequiredException ex) {
            throw new IOException(name + ": archive is encrypted, pass --password", ex);
        }
        return out;
    }

    private List<Entry> extractZip(String name, byte[] data) throws IOException {
        List<Entry> out = new ArrayList<>();
        boolean encrypted = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                out.add(new Entry(e.getName(), readLimited(zin, name + "/" + e.getName())));
            }
        } catch (ZipException ex) {
            encrypted = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("encrypt");
            if (!encrypted) throw new IOException(name + ": " + ex.getMessage(), ex);
        }
        if (!encrypted) {
            // ZipInputStream молча возвращает 0 элементов на битом архиве — считаем это ошибкой
            if (out.isEmpty() && data.length > 0) throw new IOException(name + ": no zip entries (corrupt archive?)");
            return out;
        }
        // зашифрованный zip: zip4j работает только с файлом на диске
        Path tmp = Files.createTempFile("lcbin-", ".zip");
        try {
            Files.write(tmp, data);
            try (ZipFile zf = new ZipFile(tmp.toFile(), password)) {
                out.clear();
                for (FileHeader h : zf.getFileHeaders()) {
                    if (h.isDirectory()) continue;
                    try (InputStream in = zf.getInputStream(h)) {
                        out.add(new Entry(h.getFileName(), readLimited(in, name + "/" + h.getFileName())));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return out;
    }

    /** Читает поток целиком, но не больше лимита; при превышении бросает IOException. */
    private static byte[] readLimited(InputStream in, String what) throws IOException {
        byte[] buf = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_ENTRY_BYTES + 1));
        if (buf.length > MAX_ENTRY_BYTES) throw new IOException(what + ": entry exceeds " + MAX_ENTRY_BYTES + " bytes");
        return buf;
    }
}
