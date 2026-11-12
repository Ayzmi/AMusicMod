package org.AzSofware.amusicmod.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.AzSofware.amusicmod.AMusicConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spotify Authorization Code flow with PKCE. No client secret is stored or needed.
 * Login uses a short-lived loopback server on 127.0.0.1 that exists only while the user logs in.
 */
public final class SpotifyAuth {
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state";
    private static final long LOGIN_TIMEOUT_MS = 180_000L;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Gson GSON = new Gson();

    static final class Tokens {
        String accessToken;
        String refreshToken;
        long expiresAtMs;
    }

    private final AMusicConfig config;
    private final Path tokenFile;
    private final AtomicBoolean loginRunning = new AtomicBoolean(false);
    private volatile Tokens tokens;

    public SpotifyAuth(AMusicConfig config, Path tokenFile) {
        this.config = config;
        this.tokenFile = tokenFile;
        this.tokens = loadTokens();
    }

    public boolean isLoggedIn() {
        Tokens t = tokens;
        return t != null && t.refreshToken != null && !t.refreshToken.isEmpty();
    }

    public boolean hasClientId() {
        return !config.clientId.isEmpty();
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + config.redirectPort + "/callback";
    }

    /** Valid access token (refreshed if needed), or null when not logged in. */
    public synchronized String accessToken() throws IOException {
        Tokens t = tokens;
        if (t == null || t.refreshToken == null) return null;
        if (t.accessToken == null || System.currentTimeMillis() > t.expiresAtMs - 30_000L) {
            refresh(t);
        }
        Tokens cur = tokens;
        return cur == null ? null : cur.accessToken;
    }

    /** Forces the next {@link #accessToken()} call to refresh (used after a 401). */
    public synchronized void invalidateAccessToken() {
        Tokens t = tokens;
        if (t != null) t.expiresAtMs = 0L;
    }

    public synchronized void logout() {
        tokens = null;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
        }
    }

    /**
     * Starts the browser login. {@code onUrl} receives the authorize URL (open it for the user),
     * {@code onDone} receives a short human-readable result. Both may be called from a background thread.
     */
    public void startLogin(Consumer<String> onUrl, Consumer<String> onDone) {
        if (!loginRunning.compareAndSet(false, true)) {
            onDone.accept("A login is already in progress - check your browser.");
            return;
        }
        String verifier = randomUrlSafe(64);
        String state = randomUrlSafe(16);
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), config.redirectPort), 2);
        } catch (IOException e) {
            loginRunning.set(false);
            onDone.accept("Port " + config.redirectPort + " is busy. Change \"redirectPort\" in config/amusicmod.json "
                    + "and in your Spotify app's Redirect URI.");
            return;
        }

        String url;
        try {
            url = AUTH_URL + "?" + SpotifyHttp.form(linked(
                    "client_id", config.clientId,
                    "response_type", "code",
                    "redirect_uri", redirectUri(),
                    "code_challenge_method", "S256",
                    "code_challenge", challenge(verifier),
                    "state", state,
                    "scope", SCOPES));
        } catch (Exception e) {
            closeQuietly(server);
            loginRunning.set(false);
            onDone.accept("Could not build the login URL: " + e.getMessage());
            return;
        }

        Thread t = new Thread(() -> runLoginServer(server, verifier, state, onDone), "AMusicMod-Login");
        t.setDaemon(true);
        t.start();
        onUrl.accept(url);
    }

    // ------------------------------------------------------------------ internals

    private void runLoginServer(ServerSocket server, String verifier, String state, Consumer<String> onDone) {
        String result = "Login timed out. Press the AMusicMod key again to retry.";
        try {
            long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
            outer:
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                server.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line = in.readLine(); // e.g. GET /callback?code=..&state=.. HTTP/1.1
                    String target = requestTarget(line);
                    if (target == null || !target.startsWith("/callback")) {
                        respond(s, 404, "Not found");
                        continue;
                    }
                    Map<String, String> q = parseQuery(target);
                    if (q.containsKey("error")) {
                        respond(s, 200, page("Login cancelled", "You can close this tab and go back to Minecraft."));
                        result = "Spotify login was cancelled.";
                        break outer;
                    }
                    if (!state.equals(q.get("state")) || q.get("code") == null) {
                        respond(s, 400, page("Invalid login response", "Please try again from Minecraft."));
                        result = "Spotify login failed (invalid response). Try again.";
                        break outer;
                    }
                    try {
                        exchangeCode(q.get("code"), verifier);
                        respond(s, 200, page("AMusicMod connected", "You can close this tab and go back to Minecraft."));
                        result = "Spotify connected!";
                    } catch (IOException e) {
                        respond(s, 500, page("Login failed", "Check your Client ID and Redirect URI, then try again."));
                        result = "Spotify login failed: " + e.getMessage();
                    }
                    break;
                } catch (SocketTimeoutException e) {
                    // either the overall deadline or a silent connection; loop re-checks the deadline
                }
            }
        } catch (IOException e) {
            result = "Spotify login error: " + e.getMessage();
        } finally {
            closeQuietly(server);
            loginRunning.set(false);
        }
        onDone.accept(result);
    }

    private synchronized void exchangeCode(String code, String verifier) throws IOException {
        SpotifyHttp.Response r = SpotifyHttp.request("POST", TOKEN_URL, null, SpotifyHttp.form(linked(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri(),
                "client_id", config.clientId,
                "code_verifier", verifier)));
        if (r.status() != 200) throw new IOException("token endpoint returned HTTP " + r.status());
        Tokens t = new Tokens();
        applyTokenResponse(t, r.body());
        if (t.refreshToken == null) throw new IOException("no refresh token received");
        tokens = t;
        saveTokens(t);
    }

    private void refresh(Tokens t) throws IOException {
        SpotifyHttp.Response r = SpotifyHttp.request("POST", TOKEN_URL, null, SpotifyHttp.form(linked(
                "grant_type", "refresh_token",
                "refresh_token", t.refreshToken,
                "client_id", config.clientId)));
        if (r.status() == 200) {
            applyTokenResponse(t, r.body());
            saveTokens(t);
        } else if (r.status() == 400 || r.status() == 401) {
            logout(); // refresh token revoked / client id changed -> need a fresh login
            throw new IOException("Spotify session expired, please log in again");
        } else {
            throw new IOException("token refresh returned HTTP " + r.status());
        }
    }

    private static void applyTokenResponse(Tokens t, String body) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        t.accessToken = o.get("access_token").getAsString();
        if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) {
            t.refreshToken = o.get("refresh_token").getAsString();
        }
        long expiresIn = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600L;
        t.expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L;
    }

    private Tokens loadTokens() {
        try {
            if (Files.exists(tokenFile)) {
                return GSON.fromJson(Files.readString(tokenFile, StandardCharsets.UTF_8), Tokens.class);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveTokens(Tokens t) {
        try {
            Path parent = tokenFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(tokenFile, GSON.toJson(t), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: not supported, fine
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** "GET /callback?x=1 HTTP/1.1" -> "/callback?x=1" */
    static String requestTarget(String requestLine) {
        if (requestLine == null) return null;
        int a = requestLine.indexOf(' ');
        int b = requestLine.indexOf(' ', a + 1);
        if (a < 0 || b < 0) return null;
        return requestLine.substring(a + 1, b);
    }

    static Map<String, String> parseQuery(String target) {
        Map<String, String> out = new HashMap<>();
        int qm = target.indexOf('?');
        if (qm < 0) return out;
        for (String pair : target.substring(qm + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String page(String title, String text) {
        return "<!doctype html><meta charset=utf-8><title>AMusicMod</title>"
                + "<body style=\"font-family:sans-serif;text-align:center;margin-top:15vh\">"
                + "<h2>" + title + "</h2><p>" + text + "</p></body>";
    }

    private static void respond(Socket s, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            default -> "Error";
        };
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
