package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Offline checks for timing and seek behavior; no game or external services required. */
public final class TimelineChecks {
    public static void main(String[] args) {
        var lyrics = SyncedLyrics.parse("[offset:-100]\n[00:03.25]three\n[00:01.2][00:02.20]repeat\n[00:04.000]\n[00:99]invalid");
        check(lyrics.lines().size() == 4, "multiple timestamps and invalid seconds");
        check(lyrics.indexAt(1099) == -1, "before first line");
        check(lyrics.indexAt(1100) == 0, "offset and tenths precision");
        check(lyrics.indexAt(2100) == 1, "repeated line timestamp");
        check(lyrics.indexAt(3150) == 2, "out of order lines sorted");
        check(lyrics.lines().get(lyrics.indexAt(3900)).text().isEmpty(), "blank line clears display");
        check(lyrics.indexAt(1200) == 0, "backward seek");
        check(SyncedLyrics.parse("[ar:Someone]\nplain text").indexAt(9999) == -1, "no invented timing");
        var state = new PlayerState(true, "Full title", "Artist", 1000, 10000, 100, "Album", "cover");
        var paused = state.withPlaying(false, 600);
        check(paused.progressMs() == 1500 && paused.estimatedProgress(5000) == 1500, "pause freezes progress");
        check(state.estimatedProgress(20000) == 10000, "progress clamps at track duration");
        check(paused.artwork().equals("cover") && paused.trackKey().equals(state.trackKey()), "controls retain metadata");
        checkClock();
        checkMovement();
        System.out.println("Timeline checks passed");
    }

    private static PlayerState snapshot(long position, long fetchedAt, boolean playing) {
        return new PlayerState(playing, "Track", "Artist", position, 180000, fetchedAt, "Album", "art");
    }

    private static void checkClock() {
        var clock = new LyricPlaybackClock();
        var first = snapshot(2900, 10000, true);
        check(clock.update(first, 1_000_000_000L, 10000) == 2900, "clock initializes at current progress");
        long last = clock.update(first, 1_200_000_000L, 10200);
        check(last == 3100, "monotonic interpolation");
        var poll = snapshot(3000, 10200, true);
        long next = clock.update(poll, 1_216_000_000L, 10216);
        check(next >= last, "poll drift does not rewind into the previous lyric");
        last = next;
        for (int i = 2; i < 90; i++) {
            next = clock.update(poll, 1_200_000_000L + i * 16_000_000L, 10200 + i * 16);
            check(next >= last, "negative correction never reverses playback");
            last = next;
        }
        var seek = snapshot(1000, 12000, true);
        check(clock.update(seek, 3_000_000_000L, 12000) == 1000, "real backward seek applies immediately");
        var pause = snapshot(1234, 12100, false);
        check(clock.update(pause, 3_100_000_000L, 12100) == 1234, "pause matches backend position");
        check(clock.update(pause, 5_100_000_000L, 14100) == 1234, "chat or elapsed wall time does not advance paused lyrics");
        var other = new PlayerState(true, "Other", "Artist", 0, 180000, 14100, "Album", "art");
        check(clock.update(other, 5_100_000_000L, 14100) == 0, "new song resets the clock");
        clock.reset();
        check(clock.update(first, 8_000_000_000L, 10000) == 2900, "rejoin resets interpolation");
    }

    private static void checkMovement() {
        var anchor = new LyricAnchor();
        var rotation = new Quaternionf();
        anchor.spawn(0, 64, 0, rotation, 12, 12);
        var last = new Vector3d(anchor.position);
        // Ten seconds walking/sprinting toward the original spawn point.
        for (int i = 1; i <= 600; i++) {
            double z = -i * .1;
            anchor.update(0, 64, z, rotation, 1.0 / 60, 12, 70, 16.0 / 9, 3, .5, true);
            var relative = new Vector3d(anchor.position).sub(0, 64, z);
            check(relative.isFinite(), "moving anchor stays finite");
            check(relative.z < -5, "walking never passes through the caption");
            check(anchor.position.distance(last) < .3, "walking has no anchor jumps");
            last.set(anchor.position);
        }
        // Turn at a steady rate; the caption should remain in front without edge disappearance.
        for (int i = 0; i < 360; i++) {
            rotation.identity().rotationY((float)Math.toRadians(i));
            anchor.update(0, 64, -60, rotation, 1.0 / 60, 12, 70, 16.0 / 9, 3, .5, true);
            var relative = new Vector3d(anchor.position).sub(0, 64, -60)
                    .rotate(new org.joml.Quaterniond(rotation).conjugate());
            check(relative.z < -2, "camera turn keeps the caption in front");
            check(Math.abs(relative.x / -relative.z) < 1.1, "caption stays within horizontal view while turning");
        }
        anchor.update(10000, 80, 10000, rotation, 1.0 / 60, 12, 70, 16.0 / 9, 3, .5, true);
        check(anchor.position.distance(10000, 80, 10000) < 30, "teleport carries the caption to the new location");
        anchor.update(10000, 80, 10000, rotation, 0, 12, 30, .6, 3, .5, true);
        check(anchor.position.isFinite(), "zero elapsed time and narrow FOV stay finite");
        var fixed = new LyricAnchor();
        rotation.identity();
        fixed.spawn(0, 64, 0, rotation, 12, 12);
        var original = new Vector3d(fixed.position);
        rotation.rotationY((float)Math.PI);
        fixed.update(0, 64, 0, rotation, 1.0 / 60, 12, 70, 16.0 / 9, 3, .5, false);
        check(fixed.position.distance(original) < .0001, "free world mode keeps its anchor when turning away");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
