package org.AzSofware.amusicmod;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * All "talk to the game" helpers live here, so if a Minecraft API name changes
 * only this file (plus AMusicMod and MusicHud) needs touching.
 */
final class McBridge {
    private McBridge() {}

    /** Safe to call from any thread. */
    static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[AMusic] " + message), false);
            }
        });
    }

    /** Opens the login page in the browser and also prints a clickable link in chat as a fallback. */
    static void openLoginUrl(String url) {
        Minecraft mc = Minecraft.getInstance();
        URI uri = URI.create(url);
        mc.execute(() -> {
            if (mc.player != null) {
                Component link = Component.literal("[Click here to log in to Spotify]")
                        .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(uri)).withUnderlined(true));
                mc.player.displayClientMessage(link, false);
            }
        });
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {
            // the clickable chat link above still works
        }
    }
}
