package org.AzSofware.amusicmod;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.AzSofware.amusicmod.spotify.PlayerState;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded worker/cache. HTTP, disk access and image decoding never run in a render callback. */
final class TrackAssets implements AutoCloseable {
    static final Identifier COVER = Identifier.fromNamespaceAndPath(AMusicMod.MOD_ID, "cover");
    private final AMusicConfig config;
    private final Path lyricsDir;
    private final Path musicDir;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                var thread = new Thread(r, "AMusicMod-Assets"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Assets> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Assets> entry) { return size() > 16; }
    };
    private final Map<String, SyncedLyrics> lyricCache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SyncedLyrics> entry) { return size() > 16; }
    };
    private record Assets(String key, SyncedLyrics lyrics, int[] pixels) {}
    private volatile Assets ready;
    private String requested = "";
    private String applied = "";
    private boolean hasCover;
    private PlayerState observed;
    private String requestedTrack = "";
    private int reloadVersion;
    private int observedOptions = -1;
    private SyncedLyrics lyrics = SyncedLyrics.EMPTY;
    private long nextLyricsRequest;

    TrackAssets(AMusicConfig config, Path configDir, Path musicDir) {
        this.config = config;
        lyricsDir = configDir.resolve("amusicmod-lyrics");
        this.musicDir = musicDir.toAbsolutePath().normalize();
    }

    /** Client tick only; stale worker results cannot overwrite the current track. */
    void tick(PlayerState state) {
        String key = requested;
        int options = reloadVersion * 8 + (config.lyricsEnabled ? 1 : 0)
                + (config.onlineLyrics ? 2 : 0) + (config.artworkEnabled ? 4 : 0);
        if (state != observed || options != observedOptions) {
            observed = state;
            observedOptions = options;
            String track = state == null ? "" : state.trackKey();
            if (!track.equals(requestedTrack)) lyrics = SyncedLyrics.EMPTY;
            requestedTrack = track;
            key = state == null ? "" : track + '\u0000' + state.artwork().hashCode() + ':' + options;
        }
        if (!key.equals(requested)) {
            requested = key;
            applied = "";
            hasCover = false;
            if (state != null) {
                String loadKey = key;
                worker.execute(() -> load(loadKey, state, options));
            }
            else {
                Minecraft.getInstance().getTextureManager().release(COVER);
                applied = "";
            }
        }
        Assets result = ready;
        if (result == null || !result.key.equals(requested) || result.key.equals(applied)) return;
        applied = result.key;
        lyrics = result.lyrics;
        Minecraft.getInstance().getTextureManager().release(COVER);
        if (result.pixels != null) {
            var image = new NativeImage(64, 64, false);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setPixel(x, y, result.pixels[y * 64 + x]);
            Minecraft.getInstance().getTextureManager().register(COVER, new DynamicTexture(() -> "AMusic album cover", image));
            hasCover = true;
        }
    }

    SyncedLyrics lyrics() { return lyrics; }
    boolean hasCover() { return hasCover; }
    void reload() { reloadVersion++; }

    private void load(String key, PlayerState track, int options) {
        Assets assets = cache.get(key);
        if (assets == null) {
            SyncedLyrics timeline = SyncedLyrics.EMPTY;
            int[] pixels = null;
            try {
                if (config.lyricsEnabled) {
                    String lyricKey = track.trackKey() + '\u0000' + options;
                    timeline = lyricCache.get(lyricKey);
                    if (timeline == null) {
                        timeline = loadLyrics(track);
                        lyricCache.put(lyricKey, timeline);
                    }
                }
            }
            catch (Exception e) {
                timeline = SyncedLyrics.EMPTY;
                AMusicMod.LOGGER.debug("Lyrics unavailable: {}", e.toString());
            }
            try { if (config.artworkEnabled) pixels = loadCover(track.artwork()); }
            catch (Exception e) { AMusicMod.LOGGER.debug("Artwork unavailable: {}", e.toString()); }
            assets = new Assets(key, timeline, pixels);
            cache.put(key, assets);
        }
        ready = assets;
    }

    private SyncedLyrics loadLyrics(PlayerState track) throws Exception {
        if (!track.localFile().isEmpty()) {
            Path music = Path.of(track.localFile()).toRealPath();
            if (!music.startsWith(musicDir.toRealPath())) return SyncedLyrics.EMPTY;
            Path sidecar = LocalLibrary.metadata(music).sidecar(".lrc");
            if (Files.isRegularFile(sidecar) && sidecar.toRealPath().startsWith(musicDir.toRealPath())
                    && Files.size(sidecar) <= 512000) return SyncedLyrics.parse(Files.readString(sidecar));
            return SyncedLyrics.EMPTY;
        }
        Files.createDirectories(lyricsDir);
        String name = (track.artist() + " - " + track.title()).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 160) name = name.substring(0, 160);
        Path local = lyricsDir.resolve(name + ".lrc");
        if (Files.isRegularFile(local) && Files.size(local) <= 512000) return SyncedLyrics.parse(Files.readString(local));
        if (!config.onlineLyrics || track.artist().isBlank() || track.title().isBlank()
                || System.currentTimeMillis() < nextLyricsRequest) return SyncedLyrics.EMPTY;
        String url = "https://lrclib.net/api/get?track_name=" + encode(track.title()) + "&artist_name=" + encode(track.artist());
        if (!track.album().isBlank()) url += "&album_name=" + encode(track.album());
        if (track.durationMs() >= 1000 && track.durationMs() <= 3600000) url += "&duration=" + track.durationMs() / 1000;
        nextLyricsRequest = System.currentTimeMillis() + 1000;
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "AMusicMod/1.3 (Minecraft Fabric; author Ayzmi)").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 429 || response.statusCode() == 503) {
                long seconds = 60;
                try { seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60")); }
                catch (NumberFormatException ignored) { }
                nextLyricsRequest = System.currentTimeMillis() + Math.clamp(seconds, 1L, 86400L) * 1000 + 500;
                return SyncedLyrics.EMPTY;
            }
            if (response.statusCode() != 200) return SyncedLyrics.EMPTY;
            byte[] bytes = body.readNBytes(512001);
            if (bytes.length > 512000) return SyncedLyrics.EMPTY;
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var synced = json.get("syncedLyrics");
            return synced == null || synced.isJsonNull() ? SyncedLyrics.EMPTY : SyncedLyrics.parse(synced.getAsString());
        }
    }

    private int[] loadCover(String source) throws Exception {
        if (source == null || source.isEmpty()) return null;
        byte[] bytes;
        if (source.startsWith("local:")) {
            Path image = Path.of(source.substring(6)).toRealPath();
            if (!image.startsWith(musicDir.toRealPath()) || Files.size(image) > 4_194_304) return null;
            bytes = Files.readAllBytes(image);
        } else if (source.startsWith("base64:")) {
            if (source.length() > 710000) return null;
            bytes = Base64.getDecoder().decode(source.substring(7));
        } else {
            URI uri = URI.create(source);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"i.scdn.co".equalsIgnoreCase(uri.getHost())) return null;
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) return null;
                bytes = stream.readNBytes(524289);
            }
            if (bytes.length > 524288) return null;
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return null;
                var image = reader.read(0);
                int[] pixels = new int[4096];
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
                    pixels[y * 64 + x] = image.getRGB(x * w / 64, y * h / 64);
                return pixels;
            } finally { reader.dispose(); }
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override public void close() {
        worker.shutdownNow();
        http.close();
        Minecraft.getInstance().getTextureManager().release(COVER);
    }
}
