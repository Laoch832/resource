package dev.soundconnect.r1;

import java.util.Arrays;

/** Uniform partitioned overlap-add convolution. No allocations in the audio callback. */
public final class PartitionedConvolver {
    public static final int BLOCK = 256;
    private static final int FFT = BLOCK * 2;
    private final int inputs, partitions;
    private final float[][][][] hr, hi;
    private final float[][][] xr, xi;
    private final float[][] block, output, overlap;
    private final float[] sumReal = new float[FFT], sumImag = new float[FFT];
    private final float[] cos = new float[FFT / 2], sin = new float[FFT / 2];
    private int position, head;

    /** filters[input][ear][tap]; output is stereo, delayed by BLOCK frames. */
    public PartitionedConvolver(float[][][] filters) {
        if (filters.length < 1 || filters.length > 16) throw new IllegalArgumentException("input channels");
        inputs = filters.length;
        int taps = filters[0][0].length;
        if (taps < 1 || taps > 131072) throw new IllegalArgumentException("filter length");
        partitions = (taps + BLOCK - 1) / BLOCK;
        hr = new float[inputs][2][partitions][FFT];
        hi = new float[inputs][2][partitions][FFT];
        xr = new float[inputs][partitions][FFT]; xi = new float[inputs][partitions][FFT];
        block = new float[inputs][BLOCK]; output = new float[2][BLOCK]; overlap = new float[2][BLOCK];
        for (int i = 0; i < FFT / 2; i++) {
            cos[i] = (float) Math.cos(-2 * Math.PI * i / FFT);
            sin[i] = (float) Math.sin(-2 * Math.PI * i / FFT);
        }
        for (int in = 0; in < inputs; in++) for (int ear = 0; ear < 2; ear++) {
            if (filters[in][ear].length != taps) throw new IllegalArgumentException("inconsistent filters");
            for (int p = 0; p < partitions; p++) {
                int start = p * BLOCK, length = Math.min(BLOCK, taps - start);
                System.arraycopy(filters[in][ear], start, hr[in][ear][p], 0, length);
                transform(hr[in][ear][p], hi[in][ear][p], false);
            }
        }
    }

    /** Interleaved normalized samples. Input and output may be the same array for stereo. */
    public void process(float[] input, int inputOffset, float[] destination, int offset, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < inputs; c++) block[c][position] = input[inputOffset + f * inputs + c];
            destination[offset + f * 2] = output[0][position];
            destination[offset + f * 2 + 1] = output[1][position];
            if (++position == BLOCK) { render(); position = 0; }
        }
    }

    private void render() {
        for (int c = 0; c < inputs; c++) {
            float[] r = xr[c][head], i = xi[c][head];
            System.arraycopy(block[c], 0, r, 0, BLOCK);
            Arrays.fill(r, BLOCK, FFT, 0); Arrays.fill(i, 0);
            transform(r, i, false);
        }
        for (int ear = 0; ear < 2; ear++) {
            Arrays.fill(sumReal, 0); Arrays.fill(sumImag, 0);
            for (int c = 0; c < inputs; c++) for (int p = 0; p < partitions; p++) {
                int history = head - p; if (history < 0) history += partitions;
                float[] ar = xr[c][history], ai = xi[c][history];
                float[] br = hr[c][ear][p], bi = hi[c][ear][p];
                for (int k = 0; k < FFT; k++) {
                    sumReal[k] += ar[k] * br[k] - ai[k] * bi[k];
                    sumImag[k] += ar[k] * bi[k] + ai[k] * br[k];
                }
            }
            transform(sumReal, sumImag, true);
            for (int i = 0; i < BLOCK; i++) {
                output[ear][i] = sumReal[i] + overlap[ear][i];
                overlap[ear][i] = sumReal[i + BLOCK];
            }
        }
        if (++head == partitions) head = 0;
    }

    private void transform(float[] r, float[] im, boolean inverse) {
        for (int i = 1, j = 0; i < FFT; i++) {
            int bit = FFT >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = r[i]; r[i] = r[j]; r[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int length = 2; length <= FFT; length <<= 1) {
            int half = length >> 1, stride = FFT / length;
            for (int start = 0; start < FFT; start += length) for (int j = 0; j < half; j++) {
                int a = start + j, b = a + half, w = j * stride;
                float s = inverse ? -sin[w] : sin[w];
                float tr = cos[w] * r[b] - s * im[b], ti = cos[w] * im[b] + s * r[b];
                r[b] = r[a] - tr; im[b] = im[a] - ti; r[a] += tr; im[a] += ti;
            }
        }
        if (inverse) for (int i = 0; i < FFT; i++) { r[i] /= FFT; im[i] /= FFT; }
    }

    public void reset() {
        for (float[][] input : xr) for (float[] p : input) Arrays.fill(p, 0);
        for (float[][] input : xi) for (float[] p : input) Arrays.fill(p, 0);
        for (float[] a : block) Arrays.fill(a, 0);
        for (float[] a : output) Arrays.fill(a, 0);
        for (float[] a : overlap) Arrays.fill(a, 0);
        position = head = 0;
    }
}
