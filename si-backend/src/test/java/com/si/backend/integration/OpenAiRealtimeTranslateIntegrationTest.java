package com.si.backend.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiRealtimeTranslateIntegrationTest {

    @Test
    void resample16kTo24kPreservesLittleEndianPcmAndExpandsByOneAndHalf() {
        byte[] input = new byte[4];
        writeLittleEndianPcm16(input, 0, (short) 0);
        writeLittleEndianPcm16(input, 1, (short) 1000);

        byte[] output = OpenAiRealtimeTranslateIntegration.resample16kTo24k(input);

        assertEquals(6, output.length);
        assertArrayEquals(new short[]{0, 667, 1000}, readSamples(output));
    }

    @Test
    void resample16kTo24kDropsOddTrailingByte() {
        byte[] input = new byte[]{0, 0, 10};

        byte[] output = OpenAiRealtimeTranslateIntegration.resample16kTo24k(input);

        assertEquals(4, output.length);
        assertArrayEquals(new short[]{0, 0}, readSamples(output));
    }

    private static short[] readSamples(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int offset = i * 2;
            samples[i] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
        }
        return samples;
    }

    private static void writeLittleEndianPcm16(byte[] bytes, int sampleIndex, short value) {
        int offset = sampleIndex * 2;
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }
}
