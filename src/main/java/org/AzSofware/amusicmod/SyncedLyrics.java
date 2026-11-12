package org.AzSofware.amusicmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable, sorted LRC timeline. Empty timestamped lines deliberately end a lyric. */
public record SyncedLyrics(List<Line> lines) {
    public static final SyncedLyrics EMPTY = new SyncedLyrics(List.of());
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);
    public record Line(long timeMs, String text) {}

    public SyncedLyrics { lines = List.copyOf(lines); }

    public static SyncedLyrics parse(String lrc) {
        if (lrc == null || lrc.isBlank()) return EMPTY;
        var result = new ArrayList<Line>();
        var offsetMatch = OFFSET.matcher(lrc);
        long offset = 0;
        if (offsetMatch.find()) {
            try { offset = Long.parseLong(offsetMatch.group(1)); } catch (NumberFormatException ignored) { }
        }
        offset = Math.clamp(offset, -60000L, 60000L);
        for (String raw : lrc.split("\\R")) {
            var matcher = STAMP.matcher(raw);
            var times = new ArrayList<Long>();
            int end = 0;
            while (matcher.find()) {
                long seconds = Long.parseLong(matcher.group(2));
                if (seconds >= 60) continue;
                String fraction = matcher.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                times.add(Long.parseLong(matcher.group(1)) * 60000 + seconds * 1000 + millis + offset);
                end = matcher.end();
            }
            String text = raw.substring(end).strip();
            if (text.length() > 500) text = text.substring(0, 500);
            for (long time : times) result.add(new Line(Math.max(0, time), text));
            if (result.size() > 5000) break;
        }
        result.sort(Comparator.comparingLong(Line::timeMs));
        return new SyncedLyrics(result);
    }

    public int indexAt(long progress) {
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs <= progress) { found = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return found;
    }
}
