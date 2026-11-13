package org.AzSofware.amusicmod;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.AzSofware.amusicmod.spotify.PlayerState;

import java.util.ArrayList;
import java.util.List;

/** Native world text submissions. Extraction owns animation; drawing only reads immutable frame data. */
final class LyricsRenderer {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "AMusic lyric frame");
    private static final long FADE_NANOS = 420_000_000L;
    private static final int FULL_BRIGHT = 0xF000F0;

    private record Row(FormattedCharSequence text, int width) {}
    private record CaptionDraw(List<Row> rows, double x, double y, double z, float scale, int alpha) {}
    private record Frame(List<CaptionDraw> captions, int color, boolean background, boolean throughWalls) {
        static final Frame EMPTY = new Frame(List.of(), 0, false, true);
    }

    private static final class Caption {
        final List<Row> rows;
        final LyricAnchor anchor = new LyricAnchor();
        final long born;
        final int width;
        long retired;

        Caption(List<Row> rows, long born) {
            this.rows = rows;
            this.born = born;
            width = rows.stream().mapToInt(Row::width).max().orElse(0);
        }
    }

    private final AMusicConfig config;
    private final MusicBackend backend;
    private final TrackAssets assets;
    private final LyricPlaybackClock clock = new LyricPlaybackClock();
    private SyncedLyrics timeline;
    private String trackKey;
    private PlayerState lastTrack;
    private String observedKey;
    private int index = -1;
    private Caption active, previous;
    private long lastFrame;
    private Object level;
    private Frame overlay = Frame.EMPTY;

    LyricsRenderer(AMusicConfig config, MusicBackend backend, TrackAssets assets) {
        this.config = config;
        this.backend = backend;
        this.assets = assets;
    }

    /** Called even with chat open. World changes, missing data and seeks never reuse stale anchors. */
    void extract(WorldExtractionContext context) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.max(0, (now - lastFrame) / 1e9);
        lastFrame = now;
        var track = backend.state();
        if (level != context.world()) { reset(); level = context.world(); }
        if (track == null || !config.lyricsEnabled) {
            reset();
            publish(context, Frame.EMPTY);
            return;
        }
        long progress = clock.update(track, now, System.currentTimeMillis()) + config.lyricsOffsetMs;
        var lyrics = assets.lyrics();
        if (track != lastTrack) {
            lastTrack = track;
            observedKey = track.trackKey();
        }
        String key = observedKey;
        if (!key.equals(trackKey) || lyrics != timeline) {
            active = previous = null;
            trackKey = key;
            timeline = lyrics;
            index = -1;
        }
        int nextIndex = lyrics.indexAt(progress);
        // The final line has no next timestamp; do not leave it visible through an indefinite outro.
        if (nextIndex == lyrics.lines().size() - 1 && nextIndex >= 0
                && progress - lyrics.lines().get(nextIndex).timeMs() > 12000) nextIndex = -1;
        if (nextIndex != index) {
            if (active != null) { previous = active; previous.retired = now; }
            active = null;
            index = nextIndex;
            if (index >= 0) {
                var line = lyrics.lines().get(index);
                if (!line.text().isBlank()) {
                    active = new Caption(wrap(mc.font, line.text()), now);
                    var camera = context.camera();
                    var pos = camera.position();
                    active.anchor.spawn(pos.x, pos.y, pos.z, camera.rotation(), config.lyricsDistance,
                            index * 37 + line.text().hashCode());
                }
            }
        }
        if (previous != null && now - previous.retired >= FADE_NANOS) previous = null;
        var captions = new ArrayList<CaptionDraw>(2);
        if (previous != null) captions.add(prepare(previous, context, dt, now, track.playing()));
        if (active != null) captions.add(prepare(active, context, dt, now, track.playing()));
        // Menus can cover lyrics, but typing in chat must not alter their lifecycle or timing.
        boolean visible = config.hudEnabled && !mc.options.hideGui
                && (mc.screen == null || mc.screen instanceof ChatScreen);
        Frame frame = visible ? new Frame(List.copyOf(captions), config.lyricsColor,
                config.lyricsBackground, config.lyricsThroughWalls) : Frame.EMPTY;
        publish(context, frame);
    }

    private void publish(WorldExtractionContext context, Frame frame) {
        overlay = config.worldLyrics ? Frame.EMPTY : frame;
        context.worldState().setData(FRAME, config.worldLyrics ? frame : Frame.EMPTY);
    }

    private CaptionDraw prepare(Caption caption, WorldExtractionContext context, double dt, long now, boolean playing) {
        var camera = context.camera();
        var pos = camera.position();
        var window = Minecraft.getInstance().getWindow();
        double aspect = window.getWidth() / (double)Math.max(1, window.getHeight());
        float scale = .045f * config.lyricsScale * (config.lyricsDistance / 12f);
        // Fit the longest wrapped row inside a narrow viewport, preserving the complete lyric text.
        double horizontalRoom = config.lyricsDistance * Math.tan(Math.toRadians(fov(context)) / 2) * aspect * 1.35;
        scale = (float)Math.min(scale, horizontalRoom / Math.max(1, caption.width));
        double verticalRoom = config.lyricsDistance * Math.tan(Math.toRadians(fov(context)) / 2);
        scale = (float)Math.min(scale, verticalRoom / Math.max(1, caption.rows.size() * 11));
        caption.anchor.update(pos.x, pos.y, pos.z, camera.rotation(), dt, config.lyricsDistance,
                fov(context), aspect, caption.width * scale / 2, caption.rows.size() * 11 * scale / 2,
                config.lyricsStayOnScreen);
        double appear = smooth((now - caption.born) / 300_000_000.0);
        double disappear = caption.retired == 0 ? 1 : 1 - smooth((now - caption.retired) / (double)FADE_NANOS);
        int alpha = (int)(255 * appear * disappear * (playing ? 1 : .55));
        double lift = caption.retired == 0 ? (1 - appear) * -.12 : (1 - disappear) * .18;
        return new CaptionDraw(caption.rows, caption.anchor.position.x, caption.anchor.position.y + lift,
                caption.anchor.position.z, scale, alpha);
    }

    private static List<Row> wrap(Font font, String text) {
        var rows = font.split(Component.literal(text), 190);
        var result = new ArrayList<Row>(rows.size());
        for (var row : rows) result.add(new Row(row, font.width(row)));
        return List.copyOf(result);
    }

    private static double fov(WorldExtractionContext context) {
        // Read the frame's projection, including sprinting and other dynamic FOV changes.
        return Math.toDegrees(2 * Math.atan(1 / Math.abs(context.cullProjectionMatrix().m11())));
    }

    /** Fabric's render phase consumes only data attached during extraction, never live world state. */
    static void submit(WorldRenderContext context) {
        Frame frame = context.worldState().getDataOrDefault(FRAME, Frame.EMPTY);
        if (frame.captions.isEmpty()) return;
        var camera = context.worldState().cameraRenderState;
        var pose = context.matrices();
        var collector = context.commandQueue();
        for (var caption : frame.captions) {
            if (caption.alpha < 5) continue;
            pose.pushPose();
            try {
                pose.translate(caption.x - camera.pos.x, caption.y - camera.pos.y, caption.z - camera.pos.z);
                pose.mulPose(camera.orientation);
                pose.scale(caption.scale, -caption.scale, caption.scale);
                float y = -caption.rows.size() * 11f / 2;
                int color = caption.alpha << 24 | frame.color;
                int backing = frame.background ? (caption.alpha / 4) << 24 : 0;
                for (var row : caption.rows) {
                    collector.submitText(pose, -row.width / 2f, y, row.text, true,
                            frame.throughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                            FULL_BRIGHT, color, backing, 0);
                    y += 11;
                }
            } finally { pose.popPose(); }
        }
    }

    void renderOverlay(GuiGraphics g) {
        Frame frame = overlay;
        if (frame.captions.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        for (var caption : frame.captions) {
            if (caption.alpha < 5) continue;
            int y = Math.max(45, g.guiHeight() / 4) - caption.rows.size() * 11 / 2;
            if (caption != frame.captions.getLast()) y -= 18;
            for (var row : caption.rows) {
                int x = (g.guiWidth() - row.width) / 2;
                if (frame.background) g.fill(x - 3, y - 2, x + row.width + 3, y + 10, (caption.alpha / 4) << 24);
                g.drawString(font, row.text, x, y, caption.alpha << 24 | frame.color, true);
                y += 11;
            }
        }
    }

    void reset() {
        active = previous = null;
        index = -1;
        trackKey = null;
        lastTrack = null;
        observedKey = null;
        timeline = null;
        overlay = Frame.EMPTY;
        lastFrame = 0;
        clock.reset();
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
