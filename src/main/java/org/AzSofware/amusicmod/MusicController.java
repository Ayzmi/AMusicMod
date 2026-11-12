package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;
import org.AzSofware.amusicmod.spotify.SpotifyApi;
import org.AzSofware.amusicmod.spotify.SpotifyAuth;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the single background thread. Network work never touches the render/game thread, and nothing
 * is polled while the player is not in a world or not logged in.
 */
public final class MusicController implements MusicBackend {
    private final AMusicConfig config;
    private final SpotifyAuth auth;
    private final SpotifyApi api;
    private final Consumer<String> notifyUser;
    private final Consumer<String> openUrl;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AMusicMod-Worker");
        t.setDaemon(true);
        return t;
    });

    private volatile PlayerState state;
    private volatile String status = "Connecting...";
    private volatile boolean inWorld;
    private ScheduledFuture<?> pending; // guarded by "this"
    private int errorStreak;            // worker thread only
    private String lastNotified = "";   // worker thread only

    public MusicController(AMusicConfig config, SpotifyAuth auth, SpotifyApi api,
                           Consumer<String> notifyUser, Consumer<String> openUrl) {
        this.config = config;
        this.auth = auth;
        this.api = api;
        this.notifyUser = notifyUser;
        this.openUrl = openUrl;
        reschedule(2000);
    }

    public PlayerState state() {
        return state;
    }

    /** Short text shown on the HUD when there is no track to display (empty = show nothing). */
    public String status() {
        return status;
    }

    public void setInWorld(boolean value) {
        if (inWorld != value) {
            inWorld = value;
            if (value) reschedule(500); // joined a world: refresh soon
            else state = null;
        }
    }

    // ------------------------------------------------------------------ controls (called from the game thread)

    public void togglePlayPause() {
        if (!ensureLoggedIn()) return;
        PlayerState s = state;
        boolean wasPlaying = s != null && s.playing();
        if (s != null) state = s.withPlaying(!wasPlaying, System.currentTimeMillis()); // instant HUD feedback
        run(() -> {
            if (wasPlaying) api.pause();
            else api.play();
        });
    }

    public void next() {
        if (!ensureLoggedIn()) return;
        run(api::next);
    }

    public void previous() {
        if (!ensureLoggedIn()) return;
        run(api::previous);
    }

    // ------------------------------------------------------------------ internals

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private void run(Action a) {
        exec.execute(() -> {
            try {
                a.run();
            } catch (IOException e) {
                handle(e);
            }
            afterAction();
        });
    }

    private void afterAction() {
        try {
            Thread.sleep(350); // Spotify needs a moment before the new state is visible
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        poll();
    }

    /** @return true when logged in and ready; otherwise starts login / explains what to do. */
    private boolean ensureLoggedIn() {
        if (auth.isLoggedIn()) return true;
        if (!auth.hasClientId()) {
            notifyUser.accept("Set \"clientId\" in config/amusicmod.json first (see README), then restart the game.");
            return false;
        }
        notifyUser.accept("Opening Spotify login...");
        auth.startLogin(openUrl, msg -> {
            notifyUser.accept(msg);
            reschedule(300);
        });
        return false;
    }

    private void poll() {
        long next;
        try {
            if (!auth.isLoggedIn()) {
                status = auth.hasClientId() ? "Press ] to log in to Spotify" : "Set clientId in config/amusicmod.json";
                next = 2500; // cheap idle check, no network
            } else if (!inWorld) {
                next = 2500;
            } else {
                PlayerState s = api.fetchState();
                state = inWorld ? s : null;
                errorStreak = 0;
                lastNotified = "";
                status = s == null ? "No active Spotify device - play a song in Spotify" : "";
                long base = config.pollSeconds * 1000L;
                next = (s != null && s.playing()) ? base : Math.max(base * 3, 15_000L);
            }
        } catch (IOException e) {
            handle(e);
            errorStreak = Math.min(errorStreak + 1, 4);
            next = e instanceof SpotifyApi.ApiException ae && ae.kind == SpotifyApi.Kind.RATE_LIMITED
                    ? ae.retryAfterSeconds * 1000L
                    : 10_000L * errorStreak;
        } catch (RuntimeException e) {
            AMusicMod.LOGGER.warn("Unexpected error while polling Spotify", e);
            status = "Internal error: " + e.getClass().getSimpleName();
            next = 30_000L;
        }
        reschedule(next);
    }

    private void handle(IOException e) {
        String msg;
        if (e instanceof SpotifyApi.ApiException ae) {
            switch (ae.kind) {
                case NO_DEVICE -> {
                    state = null;
                    msg = "No active Spotify device. Start playing something in the Spotify app first.";
                }
                case PREMIUM_REQUIRED -> msg = "Spotify Premium is required to control playback.";
                case NOT_LOGGED_IN -> msg = "Not logged in. Press a music key to log in.";
                case RATE_LIMITED -> msg = "Spotify rate limit, retrying in " + ae.retryAfterSeconds + "s";
                default -> msg = "Spotify error: " + ae.getMessage();
            }
        } else {
            msg = "Network error: " + e.getMessage();
        }
        AMusicMod.LOGGER.warn("AMusicMod: {}", msg);
        status = msg.length() <= 60 ? msg : msg.substring(0, 59) + "…";
        if (!msg.equals(lastNotified)) { // tell the player once per distinct problem, not every poll
            lastNotified = msg;
            notifyUser.accept(msg);
        }
    }

    private synchronized void reschedule(long delayMs) {
        if (exec.isShutdown()) return;
        if (pending != null) pending.cancel(false);
        pending = exec.schedule(this::poll, Math.max(100, delayMs), TimeUnit.MILLISECONDS);
    }

    @Override public void close() { exec.shutdownNow(); }
}
