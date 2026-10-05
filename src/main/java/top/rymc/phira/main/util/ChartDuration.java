package top.rymc.phira.main.util;

import lombok.Getter;
import top.rymc.phira.function.throwable.ThrowableIntFunction;
import top.rymc.phira.main.data.ChartInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the running time of a chart from the audio inside its chart archive.
 *
 * <p>Phira exposes no duration anywhere: the catalogue view has no such field, the
 * preview audio is a 15 second excerpt, and the archive metadata omits it too. The
 * archive is a ZIP holding the full MP3, so the length is derived from the MP3 frame
 * header. Only the archive tail and the first few KB of the audio entry are fetched,
 * which keeps this cheap even for large archives.
 */
public final class ChartDuration {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final int TAIL_BYTES = 512 * 1024;
    /** First window: enough for a typical ID3 tag plus the frame and Xing headers. */
    private static final int AUDIO_PROBE_BYTES = 32 * 1024;
    /** Second window for archives carrying a large illustrated ID3 tag. */
    private static final int AUDIO_FULL_BYTES = 512 * 1024;
    private static final String USER_AGENT = "JPhira/1";

    private static final byte[] EOCD_SIG = {0x50, 0x4B, 0x05, 0x06};
    /** Little-endian {@code PK\001\002} and {@code PK\003\004}. */
    private static final int CENTRAL_SIG = 0x02014B50;
    private static final int LOCAL_SIG = 0x04034B50;
    /** Fixed part of an Ogg page header, before the segment table. */
    private static final int OGG_PAGE_HEADER = 27;

    @Getter
    private static final GenericCache<Integer, Integer> durationCache =
            GenericCache.create(6, TimeUnit.HOURS, 20000);

    public static final ThrowableIntFunction<Integer, IOException> PROBE =
            id -> durationCache.get(id, ChartDuration::probeById);

    private ChartDuration() {
    }

    private static int probeById(int chartId) throws IOException {
        ChartInfo info = PhiraFetcher.GET_CHART_INFO.apply(chartId);
        String file = info.getFile();
        if (file == null || file.isBlank()) {
            throw new IOException("Chart has no archive: " + chartId);
        }
        Integer seconds = probe(file);
        if (seconds == null) {
            throw new IOException("Unable to read audio duration: " + chartId);
        }
        return seconds;
    }

    /** Exposed for tests: resolves the audio entry and derives the running time. */
    static Integer probe(String url) throws IOException {
        HttpClient client = CLIENT;
        byte[] head = fetch(client, url, "bytes=0-" + (AUDIO_PROBE_BYTES - 1));
        if (readInt(head, 0) != LOCAL_SIG) {
            return parseBareAudio(head);
        }

        byte[] tail = fetch(client, url, "bytes=-" + TAIL_BYTES);
        ZipEntry audio = findAudioEntry(tail);
        if (audio == null) {
            return null;
        }
        // MP3 barely compresses, so a window of the same size yields roughly the same output.
        for (int window : new int[]{AUDIO_PROBE_BYTES, AUDIO_FULL_BYTES}) {
            byte[] raw = fetch(client, url, "bytes=" + audio.offset() + "-" + (audio.offset() + window));
            byte[] audio2 = inflate(raw, audio, window);
            Integer seconds = audio.name().endsWith(".ogg")
                    ? parseOggDuration(audio2, audio.size())
                    : parseMp3Duration(audio2, audio.size());
            if (seconds != null) {
                return seconds;
            }
        }
        return null;
    }

    /** Handles archives that are plain audio files rather than a chart ZIP. */
    private static Integer parseBareAudio(byte[] head) {
        if (head.length >= 12 && head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46) {
            return parseWavDuration(head);
        }
        return parseMp3Duration(head, 0);
    }

    /**
     * WAV carries its own length, so the header alone is enough.
     * Layout: {@code RIFF size WAVE}, then {@code fmt } and {@code data} chunks.
     */
    static Integer parseWavDuration(byte[] data) {
        if (data.length < 44) {
            return null;
        }
        int byteRate = 0;
        int dataSize = 0;
        int position = 12;
        while (position + 8 <= data.length) {
            String id = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            int size = readInt(data, position + 4);
            if (id.equals("fmt ") && position + 16 <= data.length) {
                byteRate = readInt(data, position + 16);
            } else if (id.equals("data")) {
                dataSize = size;
                break;
            }
            // A non positive chunk size would step backwards and spin forever.
            if (size <= 0) {
                break;
            }
            position += 8 + size + (size & 1);
        }
        return byteRate > 0 && dataSize > 0 ? (int) Math.max(1, dataSize / (long) byteRate) : null;
    }

    /**
     * Ogg Vorbis only stores the exact length in the granule of its final page, which sits
     * at the end of a deflate stream we cannot seek into. The nominal bitrate from the
     * identification header gives an estimate instead, accurate to a few percent.
     */
    static Integer parseOggDuration(byte[] data, long declaredSize) {
        if (data == null || data.length < 47 || (data[0] & 0xFF) != 0x4F || data[1] != 0x67) {
            return null;
        }
        int segmentCount = data[26] & 0xFF;
        int body = OGG_PAGE_HEADER + segmentCount;
        if (body + 28 > data.length || (data[body] & 0xFF) != 1) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (data[body + 1 + i] != "vorbis".charAt(i)) {
                return null;
            }
        }
        int nominal = readInt(data, body + 20);
        if (nominal <= 0 || declaredSize <= 0) {
            return null;
        }
        return (int) Math.max(1, declaredSize * 8L / nominal);
    }

    /**
     * Locates the audio entry. The central directory sits immediately before the EOCD
     * record, so its size is enough to find the start without knowing the total length.
     */
    private static ZipEntry findAudioEntry(byte[] tail) {
        int eocd = lastIndexOf(tail, EOCD_SIG);
        // A full record is 22 bytes; anything shorter means we caught a stray signature.
        if (eocd < 0 || eocd + 22 > tail.length) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int directorySize = buffer.getInt(eocd + 12);
        int entries = buffer.getShort(eocd + 10) & 0xFFFF;
        return scanCentralDirectory(tail, Math.max(0, eocd - directorySize), entries);
    }

    private static ZipEntry scanCentralDirectory(byte[] tail, int start, int entries) {
        int position = start;
        for (int i = 0; i < entries && position + 46 <= tail.length; i++) {
            if (readInt(tail, position) != CENTRAL_SIG) {
                return null;
            }
            int nameLength = readShort(tail, position + 28);
            int extraLength = readShort(tail, position + 30);
            int commentLength = readShort(tail, position + 32);
            long compressed = readInt(tail, position + 20) & 0xFFFFFFFFL;
            long uncompressed = readInt(tail, position + 24) & 0xFFFFFFFFL;
            long localOffset = readInt(tail, position + 42) & 0xFFFFFFFFL;
            if (position + 46 + nameLength > tail.length) {
                return null;
            }
            String name = new String(tail, position + 46, nameLength, StandardCharsets.UTF_8).toLowerCase();
            if (name.endsWith(".mp3") || name.endsWith(".ogg")) {
                return new ZipEntry(name, localOffset, uncompressed, compressed);
            }
            position += 46 + nameLength + extraLength + commentLength;
        }
        return null;
    }

    /** Expands just enough of the deflate stream to cover the MP3 frame header. */
    private static byte[] inflate(byte[] raw, ZipEntry entry, int limit) {
        if (raw.length < 30 || readInt(raw, 0) != LOCAL_SIG) {
            return new byte[0];
        }
        int nameLength = readShort(raw, 26);
        int extraLength = readShort(raw, 28);
        int dataStart = 30 + nameLength + extraLength;
        if (dataStart >= raw.length) {
            return new byte[0];
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(raw, dataStart, raw.length - dataStart);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            while (out.size() < limit) {
                int produced = inflater.inflate(chunk);
                if (produced == 0) {
                    break;
                }
                out.write(chunk, 0, produced);
            }
        } catch (DataFormatException e) {
            return out.toByteArray();
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** Duration from the Xing/VBR header when present, otherwise from the first frame bitrate. */
    static Integer parseMp3Duration(byte[] audio, long declaredSize) {
        int frame = findFrameSync(audio, skipId3(audio));
        if (frame < 0) {
            return null;
        }
        int[] header = parseFrameHeader(audio, frame);
        if (header == null) {
            return null;
        }
        int sampleRate = header[0];
        int bitrateKbps = header[1];
        if (sampleRate <= 0 || bitrateKbps <= 0) {
            return null;
        }
        Integer vbrSeconds = vbrDuration(audio, frame, sampleRate, header[2], header[3]);
        if (vbrSeconds != null) {
            return vbrSeconds;
        }
        long audioBytes = declaredSize > 0 ? declaredSize : audio.length;
        return (int) Math.max(1, audioBytes * 8L / (bitrateKbps * 1000L));
    }

    private static Integer vbrDuration(byte[] audio, int frame, int sampleRate, int channels, int samplesPerFrame) {
        int sideInfo = channels == 1 ? 17 : 32;
        int xing = frame + 4 + sideInfo;
        if (xing + 12 > audio.length) {
            return null;
        }
        String tag = new String(audio, xing, 4, StandardCharsets.ISO_8859_1);
        if (!tag.equals("Xing") && !tag.equals("Info")) {
            return null;
        }
        int flags = readInt(audio, xing + 4);
        if ((flags & 0x1) == 0) {
            return null;
        }
        int frameCount = readInt(audio, xing + 8);
        return frameCount <= 0 ? null : (int) (frameCount * (long) samplesPerFrame / sampleRate);
    }

    private static int skipId3(byte[] audio) {
        if (audio.length > 10 && audio[0] == 0x49 && audio[1] == 0x44 && audio[2] == 0x33) {
            int size = ((audio[6] & 0x7F) << 21) | ((audio[7] & 0x7F) << 14)
                    | ((audio[8] & 0x7F) << 7) | (audio[9] & 0x7F);
            return 10 + size;
        }
        return 0;
    }

    private static int findFrameSync(byte[] audio, int from) {
        for (int i = Math.max(0, from); i + 4 <= audio.length; i++) {
            // byte is signed, so the sync byte must be compared after masking.
            if ((audio[i] & 0xFF) == 0xFF && (audio[i + 1] & 0xE0) == 0xE0) {
                return i;
            }
        }
        return -1;
    }

    /** @return {sampleRate, bitrateKbps, channels, samplesPerFrame} */
    private static int[] parseFrameHeader(byte[] audio, int frame) {
        int[] bitrates = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
        int[] rates = {44100, 48000, 32000, 0};
        int bitrateIndex = (audio[frame + 2] & 0xF0) >> 4;
        int rateIndex = (audio[frame + 2] & 0x0C) >> 2;
        if (bitrateIndex <= 0 || bitrateIndex >= 15 || rateIndex == 3) {
            return null;
        }
        int bitrate = bitrates[bitrateIndex];
        int sampleRate = rates[rateIndex];
        if (bitrate == 0 || sampleRate == 0) {
            return null;
        }
        int version = (audio[frame + 1] & 0x18) >> 3;
        int channelMode = (audio[frame + 3] & 0xC0) >> 6;
        // Layer III carries 1152 samples per frame on MPEG 1, and 576 on MPEG 2 / 2.5.
        int samplesPerFrame = version == 3 ? 1152 : 576;
        return new int[]{sampleRate, bitrate, channelMode == 3 ? 1 : 2, samplesPerFrame};
    }

    /**
     * Shared on purpose: an HttpClient owns a connection pool and a selector thread, so
     * building one per probe would leak both across a few hundred charts.
     *
     * <p>{@code /files} answers 303 to a signed CDN URL that is the only hop honouring Range.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static byte[] fetch(HttpClient client, String url, String range) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Range", range)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status + " for chart archive");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Archive probe interrupted", e);
        }
    }

    private static int lastIndexOf(byte[] data, byte[] signature) {
        outer:
        for (int i = data.length - signature.length; i >= 0; i--) {
            for (int j = 0; j < signature.length; j++) {
                if (data[i + j] != signature[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private record ZipEntry(String name, long offset, long size, long compressedSize) {
    }
}
