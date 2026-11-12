package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;

/** Monotonic frame clock: tiny polling corrections slew gently; real seeks apply immediately. */
final class LyricPlaybackClock {
    private PlayerState observed;
    private String trackKey;
    private long lastNanos;
    private double position;
    private double correction;

    long update(PlayerState state, long nowNanos, long nowMillis) {
        if (state == null) { reset(); return 0; }
        double elapsed = lastNanos == 0 ? 0 : Math.max(0, (nowNanos - lastNanos) / 1_000_000.0);
        lastNanos = nowNanos;
        if (observed != null && observed.playing()) position += elapsed;
        if (observed != state) {
            String key = state.trackKey();
            double target = state.estimatedProgress(nowMillis);
            if (!key.equals(trackKey) || observed == null || Math.abs(target - position) >= 1200
                    || observed.playing() != state.playing()) {
                position = target;
                correction = 0;
            } else {
                correction = target - position;
            }
            trackKey = key;
            observed = state;
        }
        if (state.playing()) {
            // At most 15% speed adjustment. A negative correction never reverses the timeline.
            double step = Math.clamp(correction, -elapsed * .15, elapsed * .15);
            position += step;
            correction -= step;
        }
        if (state.durationMs() > 0) position = Math.min(position, state.durationMs());
        return (long)Math.max(0, position);
    }

    void reset() {
        observed = null;
        trackKey = null;
        lastNanos = 0;
        position = correction = 0;
    }
}
