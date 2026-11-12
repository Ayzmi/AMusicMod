package org.AzSofware.amusicmod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.AzSofware.amusicmod.spotify.PlayerState;

/** Compact now-playing panel; track text layout is cached between playback snapshots. */
final class MusicHud {
    private static final int PAD = 4;
    private static final int HEIGHT = 38;

    private final AMusicConfig config;
    private final MusicBackend backend;
    private final TrackAssets assets;
    private String title = "", artist = "";
    private int lastScreenWidth;

    private PlayerState lastState;
    private String lastStatus;
    private int width = 100;
    private int statusWidth = 100;

    MusicHud(AMusicConfig config, MusicBackend backend, TrackAssets assets) {
        this.config = config;
        this.backend = backend;
        this.assets = assets;
    }

    void render(GuiGraphicsExtractor g) {
        if (!config.hudEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;
        int x = Math.min(config.hudX, Math.max(0, g.guiWidth() - 100));
        int y = Math.min(config.hudY, Math.max(0, g.guiHeight() - HEIGHT));

        PlayerState s = backend.state();
        if (s == null) {
            // No track: show a one-line status so you can always tell what the mod is doing.
            String status = backend.status();
            if (status == null || status.isEmpty()) return;
            if (!status.equals(lastStatus)) {
                lastStatus = status;
                statusWidth = Math.min(font.width(status) + PAD * 2, g.guiWidth() - x);
            }
            g.fill(x, y, x + statusWidth, y + 16, 0x99000000);
            g.text(font, font.plainSubstrByWidth(status, Math.max(0, statusWidth - PAD * 2)), x + PAD, y + 4, 0xFFFFAA00, false);
            return;
        }

        int coverSpace = config.artworkEnabled ? 36 : 0;
        if (s != lastState || lastScreenWidth != g.guiWidth()) {
            lastState = s;
            lastScreenWidth = g.guiWidth();
            int maxText = Math.max(20, Math.min(220, g.guiWidth() - x - PAD * 2 - coverSpace));
            title = font.plainSubstrByWidth(s.title(), maxText);
            artist = font.plainSubstrByWidth(s.artist(), maxText);
            width = Math.min(g.guiWidth() - x, Math.max(140, Math.max(font.width(title), font.width(artist)) + PAD * 2 + coverSpace));
        }

        boolean playing = s.playing();

        g.fill(x, y, x + width, y + HEIGHT, 0xBB101820);
        int accent = 0xFF000000 | config.hudAccentColor;
        g.fill(x, y, x + 1, y + HEIGHT, accent);
        if (config.artworkEnabled) {
            g.fill(x + PAD, y + PAD, x + PAD + 30, y + PAD + 30, 0xFF243C42);
            if (assets.hasCover()) g.blit(RenderPipelines.GUI_TEXTURED, TrackAssets.COVER,
                    x + PAD, y + PAD, 0, 0, 30, 30, 30, 30);
            else g.blit(RenderPipelines.GUI_TEXTURED, MusicScreen.ICON,
                    x + PAD, y + PAD, 0, 0, 30, 30, 30, 30);
        }
        int textX = x + PAD + coverSpace;
        g.text(font, title, textX, y + 5, playing ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        g.text(font, artist, textX, y + 17, 0xFFB3C9CF, false);

        int barX = textX;
        int barY = y + HEIGHT - 5;
        int barW = Math.max(0, width - PAD * 2 - coverSpace);
        g.fill(barX, barY, barX + barW, barY + 2, 0x66FFFFFF);
        if (s.durationMs() > 0) {
            long p = s.estimatedProgress(System.currentTimeMillis());
            int filled = (int) (barW * Math.min(1.0, (double) p / s.durationMs()));
            if (filled > 0) g.fill(barX, barY, barX + filled, barY + 2, playing ? accent : 0xFF888888);
        }
    }
}
