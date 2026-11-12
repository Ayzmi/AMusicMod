package org.AzSofware.amusicmod;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class MetadataChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("build/metadata-checks");
        Files.createDirectories(directory);
        for (int version : new int[]{3, 4}) {
            var tag = new ByteArrayOutputStream();
            frame(tag, "TIT2", text("Song title"), version);
            frame(tag, "TPE1", text("Artist name - Topic"), version);
            frame(tag, "TALB", text("Album name"), version);
            byte[] image = {1, 2, 3, 4};
            var picture = new ByteArrayOutputStream();
            picture.write(3);
            picture.write("image/png".getBytes(StandardCharsets.ISO_8859_1));
            picture.write(new byte[]{0, 3, 0});
            picture.write(image);
            frame(tag, "APIC", picture.toByteArray(), version);
            var file = new ByteArrayOutputStream();
            file.write(new byte[]{'I','D','3',(byte)version,0,0});
            file.write(sync(tag.size())); file.write(tag.toByteArray());
            Path path = directory.resolve("fallback.mp3");
            Files.write(path, file.toByteArray());
            var info = LocalMetadata.read(path);
            check(info.title().equals("Song title") && info.artist().equals("Artist name") && info.album().equals("Album name"), "ID3 metadata v" + version);
            check(Arrays.equals(info.cover(), image), "ID3 picture payload v" + version);
        }
        Path invalid = directory.resolve("Artist - Fallback.mp3");
        Files.write(invalid, new byte[]{1,2,3});
        check(LocalMetadata.read(invalid).title().equals("Fallback"), "invalid tags preserve filename fallback");
        check(LocalLibrary.metadata(Path.of("bergema sampai selamanya [bAoxY1jQnqo].mp3")).title().equals("bergema sampai selamanya"), "download ID is not part of the title");
        var results = JsonParser.parseString("""
                [{"trackName":"Song title","artistName":"Artist","duration":180,"syncedLyrics":"[00:01] line"},
                 {"trackName":"Wrong song","artistName":"Other","duration":180,"syncedLyrics":"[00:01] wrong"}]
                """).getAsJsonArray();
        check(LyricMatch.choose(results, "Song title", 180000) != null, "title and duration fallback match");
        check(LyricMatch.choose(results, "Song title", 120000) == null, "wrong recording duration rejected");
        results.add(JsonParser.parseString("{\"trackName\":\"Song title\",\"artistName\":\"Other artist\",\"duration\":180}"));
        check(LyricMatch.choose(results, "Song title", 180000) == null, "ambiguous artists rejected");
        if (args.length > 0) {
            var info = LocalMetadata.read(Path.of(args[0]));
            System.out.println("Metadata probe: title=" + info.title() + ", artist=" + info.artist() + ", cover=" + (info.cover() == null ? 0 : info.cover().length));
            if (info.cover() != null) {
                var picture = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(info.cover()));
                check(picture != null && picture.getWidth() <= 2048 && picture.getHeight() <= 2048, "embedded artwork fits HUD decoder limits");
                System.out.println("Embedded artwork decoded: " + picture.getWidth() + "x" + picture.getHeight());
            }
            try (var audio = LocalAudio.open(Path.of(args[0]))) { System.out.println("Metadata probe duration: " + audio.durationMs()); }
        }
        System.out.println("Metadata and lyric matching checks passed");
    }
    private static byte[] text(String value) {
        return ("\u0003" + value).getBytes(StandardCharsets.UTF_8);
    }
    private static void frame(ByteArrayOutputStream output, String id, byte[] value, int version) throws Exception {
        output.write(id.getBytes(StandardCharsets.ISO_8859_1));
        output.write(version == 4 ? sync(value.length) : java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
        output.write(new byte[2]); output.write(value);
    }
    private static byte[] sync(int size) { return new byte[]{(byte)(size >> 21 & 127),(byte)(size >> 14 & 127),(byte)(size >> 7 & 127),(byte)(size & 127)}; }
    private static void check(boolean value, String description) { if (!value) throw new AssertionError(description); }
}
