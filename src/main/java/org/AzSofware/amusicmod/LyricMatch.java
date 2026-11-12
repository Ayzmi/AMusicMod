package org.AzSofware.amusicmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.Locale;

/** Conservative fallback for files without artist tags; reject ambiguous recordings. */
final class LyricMatch {
    static JsonObject choose(JsonArray candidates, String title, long durationMs) {
        JsonObject selected = null;
        String artist = null;
        if (durationMs <= 0) return null;
        for (var element : candidates) {
            if (!element.isJsonObject()) continue;
            var candidate = element.getAsJsonObject();
            if (!candidate.has("trackName") || !candidate.has("artistName") || !candidate.has("duration")) continue;
            if (!normalize(candidate.get("trackName").getAsString()).equals(normalize(title))) continue;
            if (Math.abs(candidate.get("duration").getAsDouble() * 1000 - durationMs) > 3000) continue;
            String foundArtist = normalize(candidate.get("artistName").getAsString());
            if (foundArtist.isEmpty()) continue;
            if (artist != null && !artist.equals(foundArtist)) return null;
            artist = foundArtist;
            var lyrics = candidate.get("syncedLyrics");
            if (lyrics != null && !lyrics.isJsonNull() && !lyrics.getAsString().isBlank()) selected = candidate;
        }
        return selected;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private LyricMatch() { }
}
