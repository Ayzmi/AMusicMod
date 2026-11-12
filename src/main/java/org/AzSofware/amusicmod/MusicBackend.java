package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;

/** Where "now playing" comes from and who receives the play/pause/next/previous commands. */
public interface MusicBackend extends AutoCloseable {
    /** Current track, or null when nothing is known. */
    PlayerState state();

    /** Short text for the HUD when there is no track (empty = show nothing). */
    String status();

    /** Called every tick; implementations must only act when the value changes. */
    void setInWorld(boolean inWorld);

    void togglePlayPause();

    void next();

    void previous();

    @Override
    void close();
}
