package org.AzSofware.amusicmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.Desktop;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Native widgets, paged library and live appearance settings; no additional UI framework. */
final class MusicScreen extends Screen {
    static final Identifier ICON = Identifier.fromNamespaceAndPath(AMusicMod.MOD_ID, "icon.png");
    private final AMusicConfig config;
    private final MusicManager music;
    private final LocalLibrary library;
    private final TrackAssets assets;
    private int tab, page, x, panelWidth, rows;
    private long revision = -1;
    private Button playPause;

    MusicScreen(AMusicConfig config, MusicManager music, LocalLibrary library, TrackAssets assets) {
        super(Component.literal("AMusicMod"));
        this.config = config;
        this.music = music;
        this.library = library;
        this.assets = assets;
    }

    @Override protected void init() {
        panelWidth = Math.min(400, width - 20);
        x = (width - panelWidth) / 2;
        rows = Math.max(1, (height - 206) / 22);
        playPause = null;
        int third = panelWidth / 3;
        String[] tabs = {"Local music", "Appearance", "Options"};
        for (int i = 0; i < tabs.length; i++) {
            int next = i;
            var button = button(tabs[i], x + i * third, 46, third - 3, () -> { tab = next; rebuildWidgets(); });
            button.active = tab != i;
        }
        if (tab == 0) libraryWidgets();
        else if (tab == 1) appearanceWidgets();
        else optionWidgets();
        button("Done", x + panelWidth - 70, height - 28, 70, this::onClose);
    }

    private void libraryWidgets() {
        String source = music.isLocal() ? "Use external player" : "Source: " + sourceName();
        button(source, x, 74, panelWidth, () -> {
            if (!music.isLocal() && !config.backend.equalsIgnoreCase("spotify")) {
                config.mediaSource = switch (config.mediaSource) {
                    case "auto" -> "spotify";
                    case "spotify" -> "browser";
                    default -> "auto";
                };
                config.save();
            }
            music.refreshMediaSource();
            rebuildWidgets();
        });
        var tracks = library.tracks();
        int pages = Math.max(1, (tracks.size() + rows - 1) / rows);
        page = Math.clamp(page, 0, pages - 1);
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= tracks.size()) break;
            var track = tracks.get(index);
            button(font.plainSubstrByWidth(track.label(), panelWidth - 12), x, 102 + row * 22,
                    panelWidth, () -> { music.playLocal(track); rebuildWidgets(); });
        }
        button("<", x, height - 84, 32, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button(">", x + panelWidth - 32, height - 84, 32, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
        int controlWidth = (panelWidth - 6) / 3;
        button("Previous", x, height - 57, controlWidth, music::previous);
        playPause = button("Play / Pause", x + controlWidth + 3, height - 57, controlWidth, music::togglePlayPause);
        button("Next", x + (controlWidth + 3) * 2, height - 57, controlWidth, music::next);
        button("Music folder", x, height - 28, Math.min(110, panelWidth / 3), this::openFolder);
        button("Refresh", x + Math.min(110, panelWidth / 3) + 4, height - 28,
                Math.min(70, panelWidth / 4), () -> { library.refresh(); assets.reload(); page = 0; });
    }

    private void appearanceWidgets() {
        int half = panelWidth / 2;
        colorBox(x, 90, half - 4, config.lyricsColor, color -> config.lyricsColor = color, "Lyric color");
        colorBox(x + half, 90, half, config.hudAccentColor, color -> config.hudAccentColor = color, "HUD accent");
        button("Text size: " + String.format(Locale.ROOT, "%.2fx", config.lyricsScale), x, 120, half - 4, () -> {
            config.lyricsScale = config.lyricsScale >= 2 ? .5f : Math.min(2, config.lyricsScale + .25f);
            config.save(); rebuildWidgets();
        });
        button("Distance: " + (int)config.lyricsDistance + " blocks", x + half, 120, half, () -> {
            config.lyricsDistance = config.lyricsDistance >= 24 ? 6 : config.lyricsDistance + 2;
            config.save(); rebuildWidgets();
        });
        var offset = addRenderableWidget(new EditBox(font, x, 163, half - 4, 20, Component.literal("Lyric timing offset (ms)")));
        offset.setMaxLength(6);
        offset.setValue(Integer.toString(config.lyricsOffsetMs));
        offset.setResponder(value -> {
            try {
                int number = Integer.parseInt(value);
                if (number < -10000 || number > 10000) throw new NumberFormatException();
                offset.setTextColor(0xFFFFFFFF);
                config.lyricsOffsetMs = number; config.save();
            } catch (NumberFormatException ignored) { offset.setTextColor(0xFFFF7777); }
        });
        button("Local volume: " + Math.round(config.localVolume * 100) + "%", x + half, 163, half, () -> {
            config.localVolume = config.localVolume >= .99f ? 0 : Math.min(1, config.localVolume + .1f);
            config.save(); rebuildWidgets();
        });
    }

    private void optionWidgets() {
        toggle("Lyrics", 0, 78, () -> config.lyricsEnabled, value -> config.lyricsEnabled = value);
        toggle("3D lyrics", 1, 78, () -> config.worldLyrics, value -> config.worldLyrics = value);
        toggle("Stay in view", 0, 104, () -> config.lyricsStayOnScreen, value -> config.lyricsStayOnScreen = value);
        toggle("Text backing", 1, 104, () -> config.lyricsBackground, value -> config.lyricsBackground = value);
        toggle("Through blocks", 0, 130, () -> config.lyricsThroughWalls, value -> config.lyricsThroughWalls = value);
        toggle("HUD", 1, 130, () -> config.hudEnabled, value -> config.hudEnabled = value);
        toggle("Album artwork", 0, 156, () -> config.artworkEnabled, value -> config.artworkEnabled = value);
        toggle("Online lyrics", 1, 156, () -> config.onlineLyrics, value -> config.onlineLyrics = value);
    }

    private void colorBox(int left, int top, int width, int color, IntConsumer set, String name) {
        var box = addRenderableWidget(new EditBox(font, left, top, width, 20, Component.literal(name + " hex RGB")));
        box.setMaxLength(7);
        box.setValue(String.format(Locale.ROOT, "#%06X", color));
        box.setResponder(value -> {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.matches("[a-fA-F0-9]{6}")) {
                set.accept(Integer.parseInt(hex, 16));
                box.setTextColor(0xFFFFFFFF); config.save();
            } else box.setTextColor(0xFFFF7777);
        });
    }

    private void toggle(String label, int column, int y, BooleanSupplier get, Consumer<Boolean> set) {
        int half = panelWidth / 2;
        button(label + ": " + (get.getAsBoolean() ? "ON" : "OFF"), x + column * half, y, half - 4, () -> {
            set.accept(!get.getAsBoolean()); config.save(); rebuildWidgets();
        });
    }

    private Button button(String label, int left, int top, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> action.run()).bounds(left, top, width, 20).build());
    }

    private String sourceName() {
        if (config.backend.equalsIgnoreCase("spotify")) return "Spotify API";
        return switch (config.mediaSource) { case "spotify" -> "Spotify desktop"; case "browser" -> "Browser media"; default -> "Windows auto"; };
    }

    private void openFolder() {
        Thread.ofVirtual().name("AMusicMod-OpenFolder").start(() -> {
            try {
                Files.createDirectories(library.directory());
                Desktop.getDesktop().open(library.directory().toFile());
            } catch (Exception e) { McBridge.chat("Music folder: " + library.directory()); }
        });
    }

    @Override public void tick() {
        if (tab == 0 && revision != library.revision()) { revision = library.revision(); rebuildWidgets(); }
        if (playPause != null) {
            var state = music.state();
            playPause.setMessage(Component.literal(state != null && state.playing() ? "Pause" : "Play"));
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(x - 8, 5, x + panelWidth + 8, height - 5, 0xE6101820);
        g.blit(RenderPipelines.GUI_TEXTURED, ICON, x, 10, 0, 0, 28, 28, 28, 28);
        g.text(font, "AMusicMod", x + 36, 12, 0xFF000000 | config.hudAccentColor, true);
        g.text(font, "Music, your way", x + 36, 26, 0xFFAFBCC8, false);
        if (tab == 0) {
            if (library.tracks().isEmpty()) {
                g.text(font, font.plainSubstrByWidth(library.message(), panelWidth - 10), x + 5, 108, 0xFFCCD5DD, false);
                g.text(font, "Music folder > add files > Refresh", x + 5, 122, 0xFFAFBCC8, false);
            }
            int pages = Math.max(1, (library.tracks().size() + rows - 1) / rows);
            g.centeredText(font, (page + 1) + " / " + pages, width / 2, height - 78, 0xFFAFBCC8);
            var state = music.state();
            String status = state == null ? music.status() : state.title();
            g.text(font, font.plainSubstrByWidth(status, panelWidth), x, height - 99, 0xFFCCD5DD, false);
        } else if (tab == 1) {
            g.text(font, "Lyric color (HEX)", x, 77, 0xFFCCD5DD, false);
            g.text(font, "HUD accent (HEX)", x + panelWidth / 2, 77, 0xFFCCD5DD, false);
            g.text(font, "Timing offset (ms)", x, 150, 0xFFCCD5DD, false);
            g.centeredText(font, "Your lyrics will look like this", width / 2, 197, 0xFF000000 | config.lyricsColor);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
