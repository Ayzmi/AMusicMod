package org.AzSofware.amusicmod;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic decoder/library checks. Optional device smoke test uses generated silence only. */
public final class LocalAudioChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "local-audio-checks").toAbsolutePath();
        Files.createDirectories(root);
        Path wav = root.resolve("Artist - Silence.wav");
        writeSilence(wav);
        writeSilence(root.resolve("Second.wav"));
        Files.writeString(root.resolve("ignored.txt"), "not audio");
        Files.createDirectories(root.resolve("not-a-song.mp3"));
        var tracks = LocalLibrary.scan(root);
        check(tracks.size() == 2, "library ignores directories and non-audio files");
        var track = tracks.getFirst();
        check(track.title().equals("Silence") && track.artist().equals("Artist"), "filename metadata");
        check(track.sidecar(".lrc").equals(root.resolve("Artist - Silence.lrc")), "sidecar pairing");
        check(LocalLibrary.supported(Path.of("TEST.MP3")), "case-insensitive formats");
        try (var decoder = LocalAudio.open(wav)) {
            check(decoder.durationMs() == 5000, "WAV duration comes from sample count");
            long bytes = count(decoder);
            check(bytes == 22050L * 2 * 5, "WAV streaming decodes every sample exactly once");
        }
        byte[] volume = {(byte)0xFF, 0x7F, 0, (byte)0x80};
        LocalMusicBackend.applyVolume(volume, .5f);
        check((short)((volume[0] & 255) | volume[1] << 8) == 16384, "positive PCM attenuation");
        check((short)((volume[2] & 255) | volume[3] << 8) == -16384, "negative PCM attenuation");
        LocalMusicBackend.applyVolume(volume, 0);
        for (byte value : volume) check(value == 0, "mute produces silence");
        for (String fixture : new String[]{"silence.mp3", "sample.ogg"}) {
            Path file = Path.of("build", fixture);
            if (Files.isRegularFile(file)) {
                try (var decoder = LocalAudio.open(file)) {
                    check(decoder.format().getSampleRate() > 0, "fixture has an audio format");
                    check(count(decoder) > 0, "fixture decodes to PCM: " + fixture);
                    System.out.println("Decoded " + fixture);
                }
            }
        }
        if (args.length > 0 && args[0].equals("--device")) deviceSmoke(root);
        System.out.println("Local audio checks passed");
    }

    private static long count(LocalAudio.Stream decoder) throws Exception {
        long total = 0;
        byte[] chunk;
        while ((chunk = decoder.next()) != null) {
            total += chunk.length;
            check(chunk.length < 1_048_576, "decoder memory remains chunk bounded");
        }
        return total;
    }

    private static void writeSilence(Path path) throws Exception {
        var format = new AudioFormat(22050, 16, 1, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(new byte[22050 * 2 * 5]), format, 22050 * 5)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static void deviceSmoke(Path root) throws Exception {
        var config = new AMusicConfig();
        config.localVolume = 0;
        var library = new LocalLibrary(root);
        library.refresh();
        await(() -> library.tracks().size() == 2, "library refresh");
        try (var player = new LocalMusicBackend(config, library)) {
            player.play(library.tracks().getFirst());
            await(() -> player.state() != null && player.state().playing(), "audio starts");
            Thread.sleep(350);
            player.togglePlayPause();
            long frozen = player.state().progressMs();
            Thread.sleep(350);
            check(!player.state().playing(), "pause flag is retained");
            check(Math.abs(player.state().progressMs() - frozen) < 30, "device position freezes while paused");
            player.togglePlayPause();
            await(() -> player.state() != null && player.state().progressMs() > frozen + 100, "audio resumes");
            player.next();
            await(() -> player.state() != null && player.state().title().equals("Second"), "next track replaces old decoder");
            player.setInWorld(false);
            check(!player.state().playing(), "leaving the world pauses local audio");
            player.stop();
            check(player.state() == null, "stop clears playback");
            for (int i = 0; i < 12; i++) player.play(library.tracks().get(i % 2));
            await(() -> player.state() != null && player.state().title().equals("Second"), "rapid selections keep only the latest track");
            player.play(library.tracks().getFirst());
            player.setInWorld(false);
            await(() -> player.state() != null, "decoder opens after leaving the world");
            Thread.sleep(250);
            check(!player.state().playing() && player.state().progressMs() == 0, "leaving while loading cannot start audio in the menu");
        }
        System.out.println("Silent audio device smoke check passed");
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
