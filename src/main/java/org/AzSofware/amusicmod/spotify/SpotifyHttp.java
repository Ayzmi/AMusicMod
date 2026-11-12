package org.AzSofware.amusicmod.spotify;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP helper on plain HttpURLConnection (java.base only, no extra modules or threads). */
final class SpotifyHttp {
    private SpotifyHttp() {}

    record Response(int status, String body, int retryAfterSeconds) {}

    static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** @param form url-encoded body for POST/PUT, or null for an empty body (GET sends none). */
    static Response request(String method, String url, String bearer, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "AMusicMod");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);

            if (!"GET".equals(method)) {
                byte[] data = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
                if (form != null) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }

            int status = c.getResponseCode();
            String body = "";
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int retry = 0;
            String ra = c.getHeaderField("Retry-After");
            if (ra != null) {
                try { retry = Integer.parseInt(ra.trim()); } catch (NumberFormatException ignored) { }
            }
            return new Response(status, body, retry);
        } finally {
            c.disconnect();
        }
    }
}
