package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One streaming decoder/output worker, created lazily. No whole-song buffers. */
final class LocalMusicBackend implements MusicBackend {
    private final AMusicConfig config;
    private final LocalLibrary library;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                var thread = new Thread(r, "AMusicMod-LocalAudio"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Object pauseLock = new Object();
    private volatile long generation;
    private volatile boolean paused;
    private volatile SourceDataLine output;
    private volatile PlayerState state;
    private volatile String status = "Choose a track in the music menu (F7)";
    private volatile LocalLibrary.Track selected;

    LocalMusicBackend(AMusicConfig config, LocalLibrary library) {
        this.config = config;
        this.library = library;
    }
    public PlayerState state() { return state; }
    public String status() { return status; }
    public synchronized void setInWorld(boolean inWorld) {
        if (inWorld) return;
        if (output != null && !paused) togglePlayPause();
        else {
            // Leaving while a decoder is still opening must also start it in a paused state.
            paused = true;
            if (state != null) state = state.withPlaying(false, System.currentTimeMillis());
        }
    }

    synchronized void play(LocalLibrary.Track track) {
        if (!library.tracks().contains(track)) return;
        stop();
        selected = track;
        paused = false;
        status = "Opening " + track.label();
        long token = generation;
        worker.execute(() -> stream(track, token));
    }

    private void stream(LocalLibrary.Track track, long token) {
        if (token != generation) return;
        boolean finished = false;
        try (var audio = LocalAudio.open(track.file())) {
            var format = audio.format();
            if (format.getChannels() < 1 || format.getChannels() > 2 || format.getSampleRate() > 192000)
                throw new IllegalArgumentException("Use mono/stereo audio up to 192 kHz");
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            try {
                line.open(format, (int)(format.getFrameRate() * format.getFrameSize() / 10));
                String artwork = "";
                for (String extension : new String[]{".png", ".jpg", ".jpeg"}) {
                    Path cover = track.sidecar(extension);
                    if (Files.isRegularFile(cover)) { artwork = "local:" + cover; break; }
                }
                synchronized (this) {
                    if (token != generation) return;
                    output = line;
                    state = new PlayerState(!paused, track.title(), track.artist(), 0, audio.durationMs(),
                            System.currentTimeMillis(), "", artwork, track.file().toString());
                    status = "";
                    if (!paused) line.start();
                }
                long lastSnapshot = 0;
                while (token == generation && !Thread.currentThread().isInterrupted()) {
                    synchronized (pauseLock) {
                        while (paused && token == generation) pauseLock.wait();
                    }
                    if (token != generation) break;
                    byte[] pcm = audio.next();
                    if (pcm == null) { finished = true; break; }
                    applyVolume(pcm, config.localVolume);
                    int offset = 0;
                    while (offset < pcm.length && token == generation) {
                        int written = line.write(pcm, offset, pcm.length - offset);
                        if (written <= 0) break;
                        offset += written;
                    }
                    long now = System.currentTimeMillis();
                    synchronized (this) {
                        if (token == generation && now - lastSnapshot > 200) {
                            long progress = (long)(line.getLongFramePosition() * 1000 / format.getFrameRate());
                            state = new PlayerState(!paused, track.title(), track.artist(), progress, audio.durationMs(),
                                    now, "", artwork, track.file().toString());
                            lastSnapshot = now;
                        }
                    }
                }
                if (finished && token == generation) line.drain();
            } finally {
                if (output == line) output = null;
                line.close();
            }
        } catch (Exception e) {
            synchronized (this) {
                if (token == generation) {
                    state = null;
                    status = "Cannot play this file (see latest.log)";
                    AMusicMod.LOGGER.warn("Local playback failed for {}", track.file().getFileName(), e);
                }
            }
        }
        synchronized (this) {
            if (token == generation && finished) {
                var tracks = library.tracks();
                int index = tracks.indexOf(track);
                if (index >= 0 && index + 1 < tracks.size()) play(tracks.get(index + 1));
                else {
                    var snapshot = state;
                    paused = true;
                    if (snapshot != null) state = snapshot.withPlaying(false, System.currentTimeMillis());
                }
            }
        }
    }

    static void applyVolume(byte[] pcm, float volume) {
        if (volume >= .999f) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short)((pcm[i] & 255) | pcm[i + 1] << 8);
            int scaled = Math.round(sample * volume);
            pcm[i] = (byte)scaled;
            pcm[i + 1] = (byte)(scaled >> 8);
        }
    }

    public synchronized void togglePlayPause() {
        if (output == null) {
            if (selected != null) play(selected);
            return;
        }
        paused = !paused;
        SourceDataLine line = output;
        if (line != null) { if (paused) line.stop(); else line.start(); }
        PlayerState snapshot = state;
        if (snapshot != null) {
            long progress = line == null ? snapshot.progressMs() : (long)(line.getLongFramePosition() * 1000 / line.getFormat().getFrameRate());
            state = new PlayerState(!paused, snapshot.title(), snapshot.artist(), progress, snapshot.durationMs(),
                    System.currentTimeMillis(), snapshot.album(), snapshot.artwork(), snapshot.localFile());
        }
        synchronized (pauseLock) { pauseLock.notifyAll(); }
    }

    public void next() { step(1); }
    public void previous() { step(-1); }
    private void step(int delta) {
        var tracks = library.tracks();
        if (tracks.isEmpty()) return;
        int index = tracks.indexOf(selected);
        play(tracks.get(index < 0 ? 0 : Math.floorMod(index + delta, tracks.size())));
    }

    synchronized void stop() {
        generation++;
        SourceDataLine line = output;
        output = null;
        if (line != null) { line.stop(); line.flush(); line.close(); }
        state = null;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
    }

    public void close() { stop(); worker.shutdownNow(); }
}
