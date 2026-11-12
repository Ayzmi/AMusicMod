package org.AzSofware.amusicmod;

import org.AzSofware.amusicmod.spotify.PlayerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Reads and controls whatever Windows considers the current media session (Spotify desktop,
 * YouTube Music in Chrome/Edge, ...). One small PowerShell process, started on first world join,
 * that only works while you are in a world. No login, no Premium, no network.
 */
public final class WindowsMediaBackend implements MusicBackend {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final AMusicConfig config;
    private final Path scriptFile;

    private volatile PlayerState state;
    private volatile String status = "Starting media reader...";
    private volatile boolean inWorld;

    private Process process;   // guarded by "this"
    private OutputStream stdin; // guarded by "this"

    public WindowsMediaBackend(AMusicConfig config, Path scriptFile) {
        this.config = config;
        this.scriptFile = scriptFile;
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "AMusicMod-MediaStop"));
        if (!WINDOWS) status = "Media backend is Windows-only";
    }

    @Override
    public PlayerState state() {
        return state;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void setInWorld(boolean value) {
        if (inWorld == value) return;
        inWorld = value;
        if (value) {
            ensureStarted();
            send("active");
        } else {
            send("inactive");
            state = null;
        }
    }

    @Override
    public void togglePlayPause() {
        PlayerState s = state;
        if (s != null) state = s.withPlaying(!s.playing(), System.currentTimeMillis()); // instant feedback
        ensureStarted();
        send("play_pause");
    }

    @Override
    public void next() {
        ensureStarted();
        send("next");
    }

    @Override
    public void previous() {
        ensureStarted();
        send("prev");
    }

    // ------------------------------------------------------------------ process handling

    private synchronized void ensureStarted() {
        if (!WINDOWS) return;
        if (process != null && process.isAlive()) return;
        try {
            try (InputStream in = WindowsMediaBackend.class.getResourceAsStream("/assets/amusicmod/media.ps1")) {
                if (in == null) throw new IOException("media.ps1 missing from the mod jar");
                Files.createDirectories(scriptFile.getParent());
                Files.copy(in, scriptFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Process p = new ProcessBuilder(
                    "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.toAbsolutePath().toString(),
                    String.valueOf(config.pollSeconds * 1000), config.mediaSource)
                    .redirectErrorStream(true)
                    .start();
            process = p;
            stdin = p.getOutputStream();

            Thread reader = new Thread(() -> readLoop(p), "AMusicMod-Media");
            reader.setDaemon(true);
            reader.start();
            if (inWorld) send("active");
        } catch (IOException e) {
            AMusicMod.LOGGER.warn("AMusicMod: could not start the media reader", e);
            status = "Media reader failed: " + e.getMessage();
        }
    }

    @Override public synchronized void close() {
        inWorld = false;
        if (process != null) process.destroyForcibly();
        process = null;
        stdin = null;
        state = null;
    }

    public void restart() {
        boolean active = inWorld;
        close();
        setInWorld(active);
    }

    private synchronized void send(String command) {
        if (stdin == null || process == null || !process.isAlive()) return;
        try {
            stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            AMusicMod.LOGGER.debug("AMusicMod: media command failed: {}", e.getMessage());
        }
    }

    private void readLoop(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean errorShown = false;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("STATE\t")) {
                    parseState(line);
                } else if (line.equals("READY")) {
                    AMusicMod.LOGGER.info("AMusicMod: media reader ready");
                } else {
                    AMusicMod.LOGGER.warn("AMusicMod media reader: {}", line);
                    if (!errorShown && !line.isBlank()) {
                        errorShown = true;
                        status = "Media reader error (see latest.log)";
                    }
                }
            }
        } catch (IOException e) {
            AMusicMod.LOGGER.debug("AMusicMod: media reader stream closed: {}", e.getMessage());
        }
        synchronized (this) {
            if (process == p) {
                process = null;
                stdin = null;
            }
        }
        if (state == null) status = "Media reader stopped - press a music key to restart";
    }

    private void parseState(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 2 || f[1].equals("none")) {
            state = null;
            status = "No media playing - start Spotify or YouTube Music";
            return;
        }
        if (f.length < 6) return;
        try {
            boolean playing = Integer.parseInt(f[1].trim()) == 4;
            String title = f[2].isBlank() ? "Unknown" : f[2];
            long pos = Math.max(0, Long.parseLong(f[4].trim()));
            long dur = Math.max(0, Long.parseLong(f[5].trim()));
            if (!inWorld) return;
            state = new PlayerState(playing, title, f[3], pos, dur, System.currentTimeMillis(),
                    f.length > 6 ? f[6] : "", f.length > 7 ? f[7] : "");
            status = "";
        } catch (NumberFormatException ignored) {
            // malformed line: keep the previous state
        }
    }

}
