package org.AzSofware.amusicmod.spotify;

/** Immutable playback snapshot shared by external media and the local player. */
public record PlayerState(boolean playing, String title, String artist,
                          long progressMs, long durationMs, long fetchedAtMs,
                          String album, String artwork, String localFile) {

    public PlayerState(boolean playing, String title, String artist, long progressMs, long durationMs,
                       long fetchedAtMs, String album, String artwork) {
        this(playing, title, artist, progressMs, durationMs, fetchedAtMs, album, artwork, "");
    }

    public String trackKey() {
        return title + '\u0000' + artist + '\u0000' + album + '\u0000' + durationMs + '\u0000' + localFile;
    }

    public long estimatedProgress(long nowMs) {
        if (!playing) return progressMs;
        long p = progressMs + Math.max(0, nowMs - fetchedAtMs);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    /** Optimistic copy used right after a play/pause so the HUD reacts instantly. */
    public PlayerState withPlaying(boolean nowPlaying, long nowMs) {
        return new PlayerState(nowPlaying, title, artist, estimatedProgress(nowMs), durationMs, nowMs, album, artwork, localFile);
    }
}
