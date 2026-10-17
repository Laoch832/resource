package dev.soundconnect.r1;

import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class RendererTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static float[] render(PartitionedConvolver engine, float[] input, int channels, int[] chunks) {
        float[] output = new float[input.length / channels * 2];
        int offset = 0, index = 0, frames = input.length / channels;
        while (offset < frames) {
            int count = Math.min(chunks[index++ % chunks.length], frames - offset);
            engine.process(input, offset * channels, output, offset * 2, count); offset += count;
        }
        return output;
    }
    public static void main(String[] args) throws Exception {
        Random random = new Random(271828);
        for (int channels : new int[]{1, 2, 6}) {
            int taps = 933, frames = 3100;
            float[][][] filters = new float[channels][2][taps];
            float[] input = new float[frames * channels];
            for (float[][] c : filters) for (float[] ear : c) for (int i = 0; i < taps; i++) ear[i] = (random.nextFloat() - .5f) * .01f;
            for (int i = 0; i < 1100 * channels; i++) input[i] = (random.nextFloat() - .5f) * .3f;
            PartitionedConvolver engine = new PartitionedConvolver(filters);
            float[] output = render(engine, input, channels, new int[]{1, 37, 255, 1024, 17});
            double max = 0;
            for (int n = 0; n < frames; n++) for (int e = 0; e < 2; e++) {
                double expected = 0; int source = n - PartitionedConvolver.BLOCK;
                for (int k = Math.max(0, source - 1099); k < taps && k <= source; k++) for (int c = 0; c < channels; c++)
                    expected += filters[c][e][k] * input[(source - k) * channels + c];
                max = Math.max(max, Math.abs(expected - output[n * 2 + e]));
            }
            check(max < 1e-6, "direct convolution mismatch " + channels + " max=" + max);
            engine.reset();
            check(Arrays.equals(output, render(engine, input, channels, new int[]{256})), "chunk independence/reset " + channels);
        }
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        HkiProfile profile = HkiProfile.parse(bytes);
        check(profile.sampleRate == 48000 && profile.taps == 16384 && profile.directions.length == 14, "reference dimensions");
        HkiProfile.Direction left = profile.nearest(330, 90);
        check(left.azimuth == 330 && left.polar == 90 && Math.abs(left.filters[0][91]) > .1, "reference front-left filter");
        byte[] raw = Arrays.copyOfRange(bytes, 20, bytes.length);
        check(HkiProfile.parse(raw).taps == profile.taps, "raw HKI2 supported");
        check(HkiProfile.parse(Base64.getMimeEncoder(76,new byte[]{10}).encode(bytes)).taps==profile.taps,"legacy MIME Base64 RIFF");
        check(HkiProfile.parse(Base64.getEncoder().encode(raw)).taps==profile.taps,"legacy Base64 raw HKI2");
        for(byte[] invalid:new byte[][]{"aGtpMg==!".getBytes("UTF-8"),"aGtpMg= ".getBytes("UTF-8"),"====".getBytes("UTF-8")}) {
            try{HkiProfile.parse(invalid);throw new AssertionError("accepted invalid Base64");}catch(IOException expected){checks++;}
        }
        for (int offset : new int[]{4, 16, 20, 24, 28, 80, 108, 112, bytes.length - 1}) {
            byte[] invalid = bytes.clone(); invalid[offset] ^= 0x7f;
            try { HkiProfile.parse(invalid); throw new AssertionError("accepted mutation at " + offset); }
            catch (IOException expected) { checks++; }
        }
        float[][][] filters = ProfileFilters.stereo(profile, 48000);
        float[] impulse = new float[(profile.taps + 2 * PartitionedConvolver.BLOCK) * 2]; impulse[0] = 1;
        float[] output = render(new PartitionedConvolver(filters), impulse, 2, new int[]{1, 1000, 39});
        double max = 0;
        for (int n = 0; n < profile.taps; n++) for (int e = 0; e < 2; e++)
            max = Math.max(max, Math.abs(output[(n + PartitionedConvolver.BLOCK) * 2 + e] - left.filters[e][n]));
        check(max < 1e-6, "Sony measured impulse mismatch " + max);
        check(ProfileFilters.stereo(profile, 44100)[0][0].length == 15053, "44.1k resample length");
        float[] sine = new float[48000 * 4];
        for (int i = 0; i < sine.length / 2; i++) { sine[2*i] = (float)(.1 * Math.sin(i * 2 * Math.PI * 440 / 48000)); sine[2*i+1] = (float)(.1 * Math.sin(i * 2 * Math.PI * 660 / 48000)); }
        long start = System.nanoTime(); render(new PartitionedConvolver(filters), sine, 2, new int[]{1024});
        System.out.println("PASS " + checks + " checks; HKI2 profile convolution max error=" + max + "; two seconds PCM render ms=" + (System.nanoTime()-start)/1000000);
    }
}
