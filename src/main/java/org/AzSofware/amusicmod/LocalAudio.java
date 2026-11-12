package org.AzSofware.amusicmod;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.sounds.JOrbisAudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Streaming PCM decoders. Only one compressed frame/chunk is decoded at a time. */
final class LocalAudio {
    interface Stream extends AutoCloseable {
        AudioFormat format();
        long durationMs();
        byte[] next() throws Exception;
        @Override void close() throws Exception;
    }

    static Stream open(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) return new Mp3(file);
        if (name.endsWith(".ogg")) return new Ogg(file);
        if (name.endsWith(".wav")) return new Wav(file);
        throw new IOException("Supported formats: MP3, WAV, OGG Vorbis");
    }

    private static final class Wav implements Stream {
        private final AudioInputStream input;
        private final AudioFormat format;
        private final long duration;
        Wav(Path file) throws Exception {
            var original = AudioSystem.getAudioInputStream(file.toFile());
            var source = original.getFormat();
            format = new AudioFormat(source.getSampleRate(), 16, source.getChannels(), true, false);
            try { input = AudioSystem.getAudioInputStream(format, original); }
            catch (Exception e) { original.close(); throw e; }
            duration = original.getFrameLength() <= 0 ? 0 : (long)(original.getFrameLength() * 1000 / source.getFrameRate());
        }
        public AudioFormat format() { return format; }
        public long durationMs() { return duration; }
        public byte[] next() throws IOException {
            byte[] pcm = input.readNBytes(4096 - 4096 % format.getFrameSize());
            return pcm.length == 0 ? null : pcm;
        }
        public void close() throws IOException { input.close(); }
    }

    private static final class Mp3 implements Stream {
        private final Bitstream input;
        private final Decoder decoder = new Decoder();
        private Header header;
        private final AudioFormat format;
        private final long duration;
        Mp3(Path file) throws Exception {
            input = new Bitstream(new BufferedInputStream(Files.newInputStream(file)));
            try {
                header = input.readFrame();
                if (header == null) throw new IOException("Empty MP3 file");
                format = new AudioFormat(header.frequency(), 16, header.mode() == Header.SINGLE_CHANNEL ? 1 : 2, true, false);
                duration = (long)header.total_ms((int)Math.min(Integer.MAX_VALUE, Files.size(file)));
            } catch (Exception e) { input.close(); throw e; }
        }
        public AudioFormat format() { return format; }
        public long durationMs() { return duration; }
        public byte[] next() throws Exception {
            if (header == null) header = input.readFrame();
            if (header == null) return null;
            try {
                var samples = (SampleBuffer)decoder.decodeFrame(header, input);
                byte[] pcm = new byte[samples.getBufferLength() * 2];
                short[] buffer = samples.getBuffer();
                for (int i = 0; i < samples.getBufferLength(); i++) {
                    pcm[i * 2] = (byte)buffer[i];
                    pcm[i * 2 + 1] = (byte)(buffer[i] >> 8);
                }
                return pcm;
            } finally { input.closeFrame(); header = null; }
        }
        public void close() throws Exception { input.close(); }
    }

    private static final class Ogg implements Stream {
        private final JOrbisAudioStream input;
        private final AudioFormat format;
        private final long duration;
        private final ByteArrayOutputStream chunk = new ByteArrayOutputStream(16384);
        Ogg(Path file) throws IOException {
            var raw = new BufferedInputStream(Files.newInputStream(file));
            try { input = new JOrbisAudioStream(raw); }
            catch (IOException e) { raw.close(); throw e; }
            var source = input.getFormat();
            format = new AudioFormat(source.getSampleRate(), 16, source.getChannels(), true, false);
            duration = oggDuration(file, source.getSampleRate());
        }
        public AudioFormat format() { return format; }
        public long durationMs() { return duration; }
        public byte[] next() throws IOException {
            chunk.reset();
            boolean more = input.readChunk(value -> {
                int sample = (int)(Math.clamp(value, -1f, 1f) * 32767);
                chunk.write(sample & 255);
                chunk.write((sample >> 8) & 255);
            });
            return !more && chunk.size() == 0 ? null : chunk.toByteArray();
        }
        public void close() throws IOException { input.close(); }
    }

    private static long oggDuration(Path file, float sampleRate) {
        // Read only the tail, where the final Vorbis page holds the total sample count.
        try (var channel = FileChannel.open(file)) {
            ByteBuffer header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header);
            if (header.position() < 27) return 0;
            int serial = header.getInt(14);
            long size = channel.size();
            ByteBuffer tail = ByteBuffer.allocate((int)Math.min(131072, size)).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(size - tail.capacity());
            while (tail.hasRemaining() && channel.read(tail) > 0) { }
            for (int i = tail.position() - 27; i >= 0; i--) {
                if (tail.getInt(i) == 0x5367674F && tail.get(i + 4) == 0 && tail.getInt(i + 14) == serial) {
                    long samples = tail.getLong(i + 6);
                    if (samples >= 0) return (long)(samples * 1000 / sampleRate);
                }
            }
        } catch (IOException ignored) { }
        return 0;
    }

    private LocalAudio() { }
}
