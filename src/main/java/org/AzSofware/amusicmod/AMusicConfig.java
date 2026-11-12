package org.AzSofware.amusicmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tiny JSON config stored in {@code config/amusicmod.json}. */
public final class AMusicConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * "media" (default): Windows media session - works with Spotify Free and YouTube Music, no login.
     * "spotify": Spotify Web API - needs Spotify Premium on the account that owns your Spotify app.
     */
    public String backend = "media";
    /** Only for backend "spotify": Client ID from developer.spotify.com/dashboard. No secret needed. */
    public String clientId = "";
    /** Local port for the one-time login callback. Redirect URI: http://127.0.0.1:PORT/callback */
    public int redirectPort = 48721;
    public boolean hudEnabled = true;
    public int hudX = 6;
    public int hudY = 6;
    public float hudScale = .8f;
    /** How often to ask Spotify what is playing (seconds). Higher = lighter. */
    public int pollSeconds = 5;
    public boolean artworkEnabled = true;
    public boolean lyricsEnabled = true;
    /** Sends track title/artist/album/duration to LRCLIB when enabled. */
    public boolean onlineLyrics = true;
    public boolean worldLyrics = true;
    public int lyricsOffsetMs = 0;
    public float lyricsDistance = 12f;
    public float lyricsScale = 1f;
    public boolean lyricsStayOnScreen = true;
    /** Floating text has no panel by default. Optional subtle backing for bright resource packs. */
    public boolean lyricsBackground = false;
    public boolean lyricsThroughWalls = true;
    public int lyricsColor = 0xFFF1E6;
    public int hudAccentColor = 0x55DDAD;
    public String mediaSource = "auto";
    public volatile float localVolume = .7f;

    private transient Path file;

    public static AMusicConfig load(Path file) {
        AMusicConfig cfg = null;
        try {
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AMusicConfig.class);
            }
        } catch (Exception ignored) {
            // corrupt file -> fall back to defaults
        }
        if (cfg == null) cfg = new AMusicConfig();
        cfg.file = file;
        cfg.sanitize();
        cfg.save(); // writes defaults on first run so the user can find and edit the file
        return cfg;
    }

    private void sanitize() {
        if (backend == null || !(backend.equalsIgnoreCase("media") || backend.equalsIgnoreCase("spotify"))) {
            backend = "media";
        }
        if (clientId == null) clientId = "";
        clientId = clientId.trim();
        if (redirectPort < 1024 || redirectPort > 65535) redirectPort = 48721;
        if (pollSeconds < 2) pollSeconds = 2;
        if (pollSeconds > 60) pollSeconds = 60;
        if (hudX < 0) hudX = 0;
        if (hudY < 0) hudY = 0;
        if (!Float.isFinite(hudScale)) hudScale = .8f;
        hudScale = Math.clamp(hudScale, .6f, 1.2f);
        lyricsOffsetMs = Math.clamp(lyricsOffsetMs, -10000, 10000);
        if (!Float.isFinite(lyricsDistance)) lyricsDistance = 12f;
        if (!Float.isFinite(lyricsScale)) lyricsScale = 1f;
        lyricsDistance = Math.clamp(lyricsDistance, 5f, 30f);
        lyricsScale = Math.clamp(lyricsScale, .5f, 2f);
        lyricsColor &= 0xFFFFFF;
        hudAccentColor &= 0xFFFFFF;
        if (!Float.isFinite(localVolume)) localVolume = .7f;
        localVolume = Math.clamp(localVolume, 0f, 1f);
        if (!"spotify".equals(mediaSource) && !"browser".equals(mediaSource)) mediaSource = "auto";
    }

    public void save() {
        if (file == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // config is best-effort
        }
    }
}
