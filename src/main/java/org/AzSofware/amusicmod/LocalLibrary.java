package org.AzSofware.amusicmod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit refresh only: no directory watcher or per-tick disk scanning. */
final class LocalLibrary {
    record Track(Path file, String title, String artist) {
        String label() { return artist.isEmpty() ? title : artist + " - " + title; }
        Path sidecar(String extension) {
            String name = file.getFileName().toString();
            return file.resolveSibling(name.substring(0, name.lastIndexOf('.')) + extension);
        }
    }

    private final Path directory;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile List<Track> tracks = List.of();
    private volatile String message = "Refresh to find local music";
    private volatile long revision;

    LocalLibrary(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    Path directory() { return directory; }
    List<Track> tracks() { return tracks; }
    String message() { return message; }
    long revision() { return revision; }

    void refresh() {
        if (!scanning.compareAndSet(false, true)) return;
        message = "Scanning music folder...";
        Thread.ofVirtual().name("AMusicMod-Library").start(() -> {
            try {
                tracks = scan(directory);
                message = tracks.isEmpty() ? "Add MP3, WAV or OGG files, then Refresh" : tracks.size() + " local tracks";
            } catch (IOException e) {
                message = "Cannot read music folder";
                AMusicMod.LOGGER.warn("Could not scan local music", e);
            } finally {
                revision++;
                scanning.set(false);
            }
        });
    }

    static List<Track> scan(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(LocalLibrary::supported).sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(2000).map(LocalLibrary::metadata).toList();
        }
    }

    static boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg");
    }

    static Track metadata(Path path) {
        String name = path.getFileName().toString();
        String stem = name.substring(0, name.lastIndexOf('.')).replaceFirst("\\s*\\[[A-Za-z0-9_-]{11}]$", "").strip();
        int separator = stem.indexOf(" - ");
        return new Track(path.toAbsolutePath().normalize(), separator > 0 ? stem.substring(separator + 3) : stem,
                separator > 0 ? stem.substring(0, separator) : "");
    }
}
