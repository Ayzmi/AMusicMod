# AMusicMod

Client-side music HUD and floating 3D lyrics for **Minecraft 26.2 / Fabric / Java 25+**.

- Spotify desktop and browser media controls on Windows.
- Local **MP3, WAV and OGG Vorbis** playback, streamed in the background.
- Album covers, synchronized LRC lyrics, customizable text colors, size and distance.

## Use

Install Fabric API and the mod JAR. Join a world, then press **F7** for the music menu.

| Key | Action |
| --- | --- |
| F7 | Music library and appearance settings |
| F8 | Toggle HUD and lyrics |
| `]` | Play / pause |
| `[` | Previous |
| `\` | Next |

Put local music in `.minecraft/amusicmod/music/`, then click **Refresh**. Use **Music folder** to open the correct instance folder. Name files `Artist - Title.mp3` for readable metadata. Optional matching sidecars: `Artist - Title.lrc` and `Artist - Title.png` (JPG also works). Local filenames are not sent to a lyrics service. Local playback pauses when leaving a world and uses the volume in the menu.

For external music, start playback in Spotify or your browser and choose **Windows auto**, **Spotify desktop** or **Browser media** in the menu. This does not repair playback errors inside Spotify itself. Optional Spotify Web API login is configured with `backend: "spotify"`, your `clientId`, and redirect URI `http://127.0.0.1:48721/callback` in `config/amusicmod.json`.

Appearance changes save automatically. External lyric lookup uses [LRCLIB](https://lrclib.net) and sends track metadata; disable **Online lyrics** for offline use. All settings are in `config/amusicmod.json`.

## Build

```powershell
.\gradlew.bat build
```

Use `build/libs/amusicmod-1.3.0.jar`. Successful builds remove older AMusicMod JARs from `build/libs`; failed builds keep them. Replace the old JAR in your Minecraft `mods` folder manually.

## Credits

Code: Ayzmi, MIT. Floating lyric design reference: [DangoMusic](https://github.com/Sorihiko/DangoMusic); independently implemented. MP3 decoding: JLayer (LGPL-2.1, bundled license and source link). Icon: user-supplied artwork with the KusArt watermark; artwork is not covered by the code license.
