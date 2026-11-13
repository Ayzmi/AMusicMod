package org.AzSofware.amusicmod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import org.AzSofware.amusicmod.spotify.PlayerState;

/** Compact now-playing panel; track text layout is cached between playback snapshots. */
final class MusicHud {
    private static final int PAD = 3;
    private static final int HEIGHT = 28;

    private final AMusicConfig config;
    private final MusicBackend backend;
    private final TrackAssets assets;
    private String title = "", artist = "";
    private int lastScreenWidth;
    private int lastCoverSpace = -1;

    private PlayerState lastState;
    private int width = 100;

    MusicHud(AMusicConfig config, MusicBackend backend, TrackAssets assets) {
        this.config = config;
        this.backend = backend;
        this.assets = assets;
    }

    void render(GuiGraphics g) {
        if (!config.hudEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || backend.state() == null) return;
        g.pose().pushMatrix();
        try {
            g.pose().scale(config.hudScale, config.hudScale);
            renderPanel(g, mc, (int)(g.guiWidth() / config.hudScale), (int)(g.guiHeight() / config.hudScale));
        } finally { g.pose().popMatrix(); }
    }

    private void renderPanel(GuiGraphics g, Minecraft mc, int screenWidth, int screenHeight) {
        Font font = mc.font;
        int x = Math.min((int)(config.hudX / config.hudScale), Math.max(0, screenWidth - 90));
        int y = Math.min((int)(config.hudY / config.hudScale), Math.max(0, screenHeight - HEIGHT));

        PlayerState s = backend.state();
        if (s == null) return;

        int coverSpace = config.artworkEnabled ? 24 : 0;
        if (s != lastState || lastScreenWidth != screenWidth || lastCoverSpace != coverSpace) {
            lastState = s;
            lastCoverSpace = coverSpace;
            lastScreenWidth = screenWidth;
            int maxText = Math.max(20, Math.min(150, screenWidth - x - PAD * 2 - coverSpace));
            title = font.plainSubstrByWidth(s.title(), maxText);
            artist = font.plainSubstrByWidth(s.artist(), maxText);
            width = Math.min(screenWidth - x, Math.max(90, Math.max(font.width(title), font.width(artist)) + PAD * 2 + coverSpace));
        }

        boolean playing = s.playing();

        g.fill(x, y, x + width, y + HEIGHT, 0x8810161D);
        int accent = 0xFF000000 | config.hudAccentColor;
        g.fill(x, y, x + 1, y + HEIGHT, accent);
        if (config.artworkEnabled) {
            if (assets.hasCover()) g.blit(RenderPipelines.GUI_TEXTURED, TrackAssets.COVER,
                    x + PAD, y + PAD, 0, 0, 20, 20, 20, 20);
            else {
                g.fill(x + PAD, y + PAD, x + PAD + 20, y + PAD + 20, 0x442C3942);
                g.drawString(font, "♪", x + PAD + 6, y + PAD + 5, accent, false);
            }
        }
        int textX = x + PAD + coverSpace;
        g.drawString(font, title, textX, y + 3, playing ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        g.drawString(font, artist, textX, y + 13, 0xFFAFBBC4, false);

        int barX = textX;
        int barY = y + HEIGHT - 3;
        int barW = Math.max(0, width - PAD * 2 - coverSpace);
        g.fill(barX, barY, barX + barW, barY + 1, 0x44FFFFFF);
        if (s.durationMs() > 0) {
            long p = s.estimatedProgress(System.currentTimeMillis());
            int filled = (int) (barW * Math.min(1.0, (double) p / s.durationMs()));
            if (filled > 0) g.fill(barX, barY, barX + filled, barY + 1, playing ? accent : 0xFF888888);
        }
    }
}
