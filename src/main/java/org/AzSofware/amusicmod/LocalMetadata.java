package org.AzSofware.amusicmod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Bounded ID3v2.3/v2.4 metadata reader; audio frames are never loaded into memory. */
final class LocalMetadata {
    record Info(String title, String artist, String album, byte[] cover) {}
    private static final int MAX_TAG = 4 * 1024 * 1024;

    static Info read(Path file) {
        var fallback = LocalLibrary.metadata(file);
        String title = fallback.title(), artist = fallback.artist(), album = "";
        byte[] cover = null;
        try (var input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(10);
            if (header.length != 10 || header[0] != 'I' || header[1] != 'D' || header[2] != '3')
                return new Info(title, artist, album, null);
            int version = header[3] & 255;
            int size = syncSize(header, 6);
            if ((version != 3 && version != 4) || size < 0 || size > MAX_TAG || (header[5] & 0x80) != 0)
                return new Info(title, artist, album, null);
            byte[] tag = input.readNBytes(size);
            int offset = 0;
            if ((header[5] & 0x40) != 0 && tag.length >= 4) {
                int extended = version == 4 ? syncSize(tag, 0) : integer(tag, 0);
                offset = version == 4 ? extended : extended + 4;
            }
            while (offset >= 0 && offset + 10 <= tag.length) {
                String id = new String(tag, offset, 4, StandardCharsets.ISO_8859_1);
                int length = version == 4 ? syncSize(tag, offset + 4) : integer(tag, offset + 4);
                if (length <= 0 || length > tag.length - offset - 10) break;
                int flags = tag[offset + 9] & 255;
                // Compressed, encrypted, grouped or unsynchronized frames need extra decoding.
                if (flags == 0) {
                    int start = offset + 10;
                    switch (id) {
                        case "TIT2" -> { String value = text(tag, start, length); if (!value.isBlank()) title = value; }
                        case "TPE1" -> { String value = text(tag, start, length); if (!value.isBlank()) artist = value; }
                        case "TALB" -> album = text(tag, start, length);
                        case "APIC" -> { if (cover == null) cover = picture(tag, start, length); }
                        default -> { }
                    }
                }
                offset += 10 + length;
            }
        } catch (IOException | RuntimeException ignored) {
            // Missing/malformed tags never prevent audio playback or filename metadata.
        }
        return new Info(title, artist.replaceFirst("(?i)\\s+-\\s+Topic$", "").strip(), album, cover);
    }

    private static String text(byte[] bytes, int start, int size) {
        if (size < 2) return "";
        Charset charset = switch (bytes[start]) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        String value = new String(bytes, start + 1, size - 1, charset);
        int terminator = value.indexOf('\0');
        if (terminator >= 0) value = value.substring(0, terminator);
        return value.strip();
    }

    private static byte[] picture(byte[] tag, int start, int size) {
        int end = start + size;
        int encoding = tag[start] & 255;
        int cursor = start + 1;
        while (cursor < end && tag[cursor] != 0) cursor++; // MIME
        cursor += 2; // terminator and picture type
        if (encoding == 1 || encoding == 2) {
            while (cursor + 1 < end && (tag[cursor] != 0 || tag[cursor + 1] != 0)) cursor += 2;
            cursor += 2;
        } else {
            while (cursor < end && tag[cursor] != 0) cursor++;
            cursor++;
        }
        return cursor < end ? Arrays.copyOfRange(tag, cursor, end) : null;
    }

    private static int integer(byte[] bytes, int start) { return ByteBuffer.wrap(bytes, start, 4).order(ByteOrder.BIG_ENDIAN).getInt(); }
    private static int syncSize(byte[] bytes, int start) {
        int size = 0;
        for (int i = 0; i < 4; i++) {
            if ((bytes[start + i] & 0x80) != 0) return -1;
            size = (size << 7) | bytes[start + i];
        }
        return size;
    }
    private LocalMetadata() { }
}
