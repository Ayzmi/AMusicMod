package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;

/** Routes the existing HUD/keys to exactly one chosen music source. */
final class MusicManager implements MusicBackend {
    private final MusicBackend external;
    private final LocalMusicBackend local;
    private boolean localMode;
    private boolean inWorld;

    MusicManager(MusicBackend external, LocalMusicBackend local) { this.external = external; this.local = local; }
    private MusicBackend active() { return localMode ? local : external; }
    boolean isLocal() { return localMode; }
    public PlayerState state() { return active().state(); }
    public String status() { return active().status(); }
    public void togglePlayPause() { active().togglePlayPause(); }
    public void next() { active().next(); }
    public void previous() { active().previous(); }
    public void setInWorld(boolean value) {
        if (inWorld == value) return;
        inWorld = value;
        external.setInWorld(value && !localMode);
        local.setInWorld(value && localMode);
    }

    void playLocal(LocalLibrary.Track track) {
        if (!localMode) {
            var state = external.state();
            if (state != null && state.playing()) external.togglePlayPause();
            external.setInWorld(false);
        }
        localMode = true;
        local.play(track);
    }

    void useExternal() {
        local.stop();
        localMode = false;
        external.setInWorld(inWorld);
    }

    void refreshMediaSource() {
        useExternal();
        if (external instanceof WindowsMediaBackend media) media.restart();
    }

    public void close() { local.close(); external.close(); }
}
