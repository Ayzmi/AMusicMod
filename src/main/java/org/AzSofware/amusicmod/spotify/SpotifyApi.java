package org.AzSofware.amusicmod.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Thin wrapper around the Spotify Web API "player" endpoints. Always called from the worker thread. */
public final class SpotifyApi {
    private static final String BASE = "https://api.spotify.com/v1/me/player";

    public enum Kind { NOT_LOGGED_IN, NO_DEVICE, PREMIUM_REQUIRED, RATE_LIMITED, OTHER }

    public static final class ApiException extends IOException {
        public final Kind kind;
        public final int retryAfterSeconds;

        ApiException(Kind kind, String message, int retryAfterSeconds) {
            super(message);
            this.kind = kind;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final SpotifyAuth auth;

    public SpotifyApi(SpotifyAuth auth) {
        this.auth = auth;
    }

    /** @return current state, or null when nothing is active (no device / idle). */
    public PlayerState fetchState() throws IOException {
        SpotifyHttp.Response r = call("GET", BASE + "?additional_types=episode");
        if (r.status() == 204 || r.body().isEmpty()) return null;
        if (r.status() != 200) throw new ApiException(Kind.OTHER, "HTTP " + r.status() + " " + snippet(r.body()), 0);
        return parseState(r.body(), System.currentTimeMillis());
    }

    public void play() throws IOException { control("PUT", "/play"); }

    public void pause() throws IOException { control("PUT", "/pause"); }

    public void next() throws IOException { control("POST", "/next"); }

    public void previous() throws IOException { control("POST", "/previous"); }

    // ------------------------------------------------------------------ internals

    private void control(String method, String path) throws IOException {
        SpotifyHttp.Response r = call(method, BASE + path);
        int s = r.status();
        if (s >= 200 && s < 300) return;
        if (s == 404) throw new ApiException(Kind.NO_DEVICE, "No active Spotify device", 0);
        if (s == 403 && r.body().contains("PREMIUM")) {
            throw new ApiException(Kind.PREMIUM_REQUIRED, "Spotify Premium is required for playback control", 0);
        }
        throw new ApiException(Kind.OTHER, "HTTP " + s + " " + snippet(r.body()), 0);
    }

    private static String snippet(String body) {
        String b = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return b.length() <= 110 ? b : b.substring(0, 110);
    }

    private SpotifyHttp.Response call(String method, String url) throws IOException {
        String token = auth.accessToken();
        if (token == null) throw new ApiException(Kind.NOT_LOGGED_IN, "Not logged in to Spotify", 0);
        SpotifyHttp.Response r = SpotifyHttp.request(method, url, token, null);
        if (r.status() == 401) {
            auth.invalidateAccessToken();
            token = auth.accessToken();
            if (token == null) throw new ApiException(Kind.NOT_LOGGED_IN, "Not logged in to Spotify", 0);
            r = SpotifyHttp.request(method, url, token, null);
        }
        if (r.status() == 429) {
            throw new ApiException(Kind.RATE_LIMITED, "Rate limited by Spotify", Math.max(1, r.retryAfterSeconds()));
        }
        return r;
    }

    /** Package-visible for quick offline testing. */
    public static PlayerState parseState(String json, long nowMs) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        boolean playing = bool(root, "is_playing");
        long progress = lng(root, "progress_ms");
        JsonObject item = obj(root, "item");

        String title = "Spotify";
        String artist = "";
        long duration = 0;
        String album = "";
        String artwork = "";
        if (item != null) {
            title = str(item, "name", "Unknown");
            duration = lng(item, "duration_ms");
            JsonObject collection = obj(item, "album");
            if (collection != null) album = str(collection, "name", "");
            JsonElement images = (collection != null ? collection : item).get("images");
            if (images != null && images.isJsonArray()) {
                // Spotify orders largest first; use the smallest image for a tiny HUD.
                for (JsonElement image : images.getAsJsonArray()) {
                    if (image.isJsonObject()) artwork = str(image.getAsJsonObject(), "url", artwork);
                }
            }
            JsonElement arr = item.get("artists");
            if (arr != null && arr.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement e : (JsonArray) arr) {
                    if (!e.isJsonObject()) continue;
                    String n = str(e.getAsJsonObject(), "name", "");
                    if (n.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(n);
                }
                artist = sb.toString();
            }
            if (artist.isEmpty()) {
                JsonObject show = obj(item, "show"); // podcast episode
                if (show != null) artist = str(show, "name", "");
            }
        } else if ("ad".equals(str(root, "currently_playing_type", ""))) {
            title = "Advertisement";
        }
        return new PlayerState(playing, title, artist, progress, duration, nowMs, album, artwork);
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0L;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
