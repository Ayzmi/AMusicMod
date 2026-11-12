package org.AzSofware.amusicmod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.AzSofware.amusicmod.spotify.SpotifyApi;
import org.AzSofware.amusicmod.spotify.SpotifyAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class AMusicMod implements ClientModInitializer {
    public static final String MOD_ID = "amusicmod";
    public static final Logger LOGGER = LoggerFactory.getLogger("AMusicMod");

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        AMusicConfig config = AMusicConfig.load(configDir.resolve("amusicmod.json"));

        MusicBackend external;
        if (config.backend.equalsIgnoreCase("spotify")) {
            SpotifyAuth auth = new SpotifyAuth(config, configDir.resolve("amusicmod-tokens.json"));
            external = new MusicController(config, auth, new SpotifyApi(auth), McBridge::chat, McBridge::openLoginUrl);
        } else {
            external = new WindowsMediaBackend(config, configDir.resolve("amusicmod-media.ps1"));
        }
        LocalLibrary library = new LocalLibrary(FabricLoader.getInstance().getGameDir().resolve("amusicmod/music"));
        MusicManager backend = new MusicManager(external, new LocalMusicBackend(config, library));
        TrackAssets assets = new TrackAssets(config, configDir, library.directory());
        MusicHud hud = new MusicHud(config, backend, assets);
        LyricsRenderer lyrics = new LyricsRenderer(config, backend, assets);
        LevelExtractionEvents.END_EXTRACTION.register(lyrics::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(LyricsRenderer::submit);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            backend.close();
            assets.close();
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping playPause = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amusicmod.play_pause", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, category));
        KeyMapping previous = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amusicmod.previous", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, category));
        KeyMapping next = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amusicmod.next", InputConstants.Type.KEYSYM, InputConstants.KEY_BACKSLASH, category));
        KeyMapping toggleHud = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amusicmod.toggle_hud", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, category));
        KeyMapping openMenu = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amusicmod.open_menu", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, category));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"),
                (graphics, tickCounter) -> {
                    hud.render(graphics);
                    if (config.hudEnabled) lyrics.renderOverlay(graphics);
                });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            backend.setInWorld(client.player != null);
            if (client.player == null) lyrics.reset();
            assets.tick(client.player != null ? backend.state() : null);
            while (playPause.consumeClick()) backend.togglePlayPause();
            while (previous.consumeClick()) backend.previous();
            while (next.consumeClick()) backend.next();
            while (openMenu.consumeClick()) {
                if (client.gui.screen() == null) {
                    library.refresh();
                    client.setScreenAndShow(new MusicScreen(config, backend, library, assets));
                }
            }
            while (toggleHud.consumeClick()) {
                config.hudEnabled = !config.hudEnabled;
                config.save();
            }
        });

        LOGGER.info("AMusicMod loaded (backend: {})", config.backend);
    }
}
