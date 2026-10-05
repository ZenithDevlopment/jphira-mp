package top.rymc.phira.main.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChartDurationParseTest {

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static void putAscii(byte[] data, int offset, String text) {
        System.arraycopy(text.getBytes(StandardCharsets.ISO_8859_1), 0, data, offset, text.length());
    }

    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo. */
    private static byte[] mp3Frame() {
        byte[] data = new byte[64];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFB;
        data[2] = (byte) 0x90;
        data[3] = 0x00;
        return data;
    }

    @Test
    void xingHeaderGivesTheExactFrameCount() {
        byte[] data = mp3Frame();
        int xing = 4 + 32;
        putAscii(data, xing, "Xing");
        putInt(data, xing + 4, 0x1);
        putInt(data, xing + 8, 1000);

        // 1000 frames * 1152 samples / 44100 Hz
        assertThat(ChartDuration.parseMp3Duration(data, 0)).isEqualTo(26);
    }

    @Test
    void infoTagIsAcceptedAsWell() {
        byte[] data = mp3Frame();
        int info = 4 + 32;
        putAscii(data, info, "Info");
        putInt(data, info + 4, 0x1);
        putInt(data, info + 8, 44100);

        assertThat(ChartDuration.parseMp3Duration(data, 0)).isEqualTo(1152);
    }

    @Test
    void constantBitrateFallsBackToTheFileSize() {
        byte[] data = mp3Frame();
        // 128 kbps for 128000 bytes is exactly 8 seconds.
        assertThat(ChartDuration.parseMp3Duration(data, 128000)).isEqualTo(8);
    }

    @Test
    void id3TagIsSkipped() {
        byte[] data = new byte[128];
        putAscii(data, 0, "ID3");
        data[3] = 0x03;
        data[9] = 0x00;
        // Declared size 0 keeps the frame right after the 10 byte header.
        System.arraycopy(mp3Frame(), 0, data, 10, 4);
        assertThat(ChartDuration.parseMp3Duration(data, 128000)).isEqualTo(8);
    }

    @Test
    void garbageIsRejected() {
        assertThat(ChartDuration.parseMp3Duration(new byte[0], 0)).isNull();
        assertThat(ChartDuration.parseMp3Duration("not audio at all".getBytes(StandardCharsets.ISO_8859_1), 0))
                .isNull();
    }

    @Test
    void oggUsesTheNominalBitrate() {
        byte[] data = new byte[64];
        putAscii(data, 0, "OggS");
        data[26] = 1;
        data[27] = 30;
        int body = 27 + 1;
        data[body] = 0x01;
        putAscii(data, body + 1, "vorbis");
        putInt(data, body + 12, 44100);
        putInt(data, body + 16, 0);
        putInt(data, body + 20, 128000);

        assertThat(ChartDuration.parseOggDuration(data, 256000)).isEqualTo(16);
    }

    @Test
    void oggNeedsItsIdentificationHeader() {
        byte[] data = new byte[64];
        putAscii(data, 0, "OggS");
        assertThat(ChartDuration.parseOggDuration(data, 256000)).isNull();
        assertThat(ChartDuration.parseOggDuration(null, 256000)).isNull();
    }

    @Test
    void wavIsReadFromItsChunks() {
        byte[] data = new byte[44];
        putAscii(data, 0, "RIFF");
        putInt(data, 4, 36);
        putAscii(data, 8, "WAVE");
        // fmt chunk at 12: size, audioFormat(2) + channels(2), sampleRate, byteRate
        putAscii(data, 12, "fmt ");
        putInt(data, 16, 16);
        putInt(data, 20, 1 | (2 << 16));
        putInt(data, 24, 44100);
        putInt(data, 28, 176400);
        putAscii(data, 36, "data");
        putInt(data, 40, 529200);

        // 529200 bytes at 176400 bytes per second is 3 seconds.
        assertThat(ChartDuration.parseWavDuration(data)).isEqualTo(3);
    }

    @Test
    void truncatedWavIsRejected() {
        assertThat(ChartDuration.parseWavDuration(new byte[8])).isNull();
    }
}
