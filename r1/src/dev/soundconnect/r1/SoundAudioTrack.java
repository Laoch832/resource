package dev.soundconnect.r1;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM interception at NetEase's actual AudioTrack creation sites. */
public final class SoundAudioTrack extends AudioTrack {
    private static final int CAPACITY = 16384;
    private PartitionedConvolver renderer;
    private final int encoding;
    private final float[] samples = new float[CAPACITY / 2];
    private final byte[] converted = new byte[CAPACITY];
    private final ByteBuffer outputBuffer = ByteBuffer.wrap(converted).order(ByteOrder.LITTLE_ENDIAN);
    private int pendingOffset, pendingLength;
    private boolean delegating;
    private long frames;

    public SoundAudioTrack(AudioAttributes attributes, AudioFormat format, int size, int mode, int session) {
        super(attributes, format, size, mode, session);
        encoding = format.getEncoding();
        configure(format.getSampleRate(), format.getChannelCount(), mode);
    }
    public SoundAudioTrack(int stream, int rate, int channels, int encoding, int size, int mode) {
        super(stream, rate, channels, encoding, size, mode);
        this.encoding = encoding;
        configure(rate, channels == AudioFormat.CHANNEL_OUT_STEREO ? 2 : 1, mode);
    }
    private void configure(int rate, int channels, int mode) {
        if (mode != MODE_STREAM || channels != 2 || (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)) return;
        try {
            if (!ProfileStore.enabled()) return;
            HkiProfile profile = ProfileStore.read();
            renderer = new PartitionedConvolver(ProfileFilters.stereo(profile, rate));
            Log.i("SonyR1", "active: stereo BRIR; sampleRate=" + rate + "; taps=" + profile.taps + "; latency=256 frames");
        } catch (Exception e) { Log.e("SonyR1", "Profile unavailable; original PCM preserved", e); }
    }

    public static AudioTrack create(AudioAttributes attributes, int flags, int rate, int channels, int encoding, int size, int mode, int session) {
        try {
            if (attributes == null) attributes = new AudioAttributes.Builder().setUsage(1).setContentType(2).setFlags(flags).build();
            AudioFormat format = new AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(channels).build();
            SoundAudioTrack result = new SoundAudioTrack(attributes, format, size, mode, session > 0 ? session : 0);
            if (result.getState() == STATE_INITIALIZED) return result;
            result.release(); return null;
        } catch (Exception e) { Log.e("SonyR1", "AudioTrack creation failed", e); return null; }
    }

    /** Unsent output is retained. The caller consumes its input only once, even after partial writes. */
    private int drain(int mode) {
        while (pendingLength > 0) {
            int result;
            delegating = true;
            try {
                outputBuffer.position(pendingOffset); outputBuffer.limit(pendingOffset + pendingLength);
                result = super.write(outputBuffer, pendingLength, mode);
            }
            finally { delegating = false; }
            if (result < 0) return result;
            if (result == 0) return 0;
            pendingOffset += result; pendingLength -= result;
            if (mode == WRITE_NON_BLOCKING && pendingLength > 0) return 0;
        }
        pendingOffset = 0; return 1;
    }

    private void convert(int count) {
        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
        for (int i = 0; i < count * 2; i++) if (Float.isNaN(samples[i]) || Float.isInfinite(samples[i])) samples[i] = 0;
        renderer.process(samples, 0, samples, 0, count);
        ByteBuffer out = outputBuffer; out.clear();
        for (int i = 0; i < count * 2; i++) {
            float value = Math.max(-1f, Math.min(1f, samples[i] * .5f));
            if (bytes == 4) out.putFloat(value);
            else out.putShort((short)Math.max(-32768, Math.min(32767, Math.round(value * 32768f))));
        }
        pendingOffset = 0; pendingLength = count * 2 * bytes;
        frames += count;
        if (frames == count) Log.i("SonyR1", "PCM convolution executed; first input frames=" + count);
    }

    @Override public synchronized int write(byte[] data, int offset, int size) { return write(data, offset, size, WRITE_BLOCKING); }
    @Override public synchronized int write(byte[] data, int offset, int size, int mode) {
        if (delegating || renderer == null) return super.write(data, offset, size, mode);
        if (data == null || offset < 0 || size < 0 || offset > data.length - size || (mode != WRITE_BLOCKING && mode != WRITE_NON_BLOCKING)) return ERROR_BAD_VALUE;
        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2, frameSize = bytes * 2;
        if (size % frameSize != 0) return ERROR_BAD_VALUE;
        int accepted = 0;
        while (accepted < size) {
            int state = drain(mode); if (state <= 0) return accepted > 0 ? accepted : state;
            int count = Math.min(size - accepted, CAPACITY) / frameSize;
            ByteBuffer in = ByteBuffer.wrap(data, offset + accepted, count * frameSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count * 2; i++) samples[i] = bytes == 4 ? in.getFloat() : in.getShort() / 32768f;
            convert(count); accepted += count * frameSize;
        }
        drain(mode); return accepted;
    }

    @Override public synchronized int write(short[] data, int offset, int size) { return write(data, offset, size, WRITE_BLOCKING); }
    @Override public synchronized int write(short[] data, int offset, int size, int mode) {
        if (delegating || renderer == null) return super.write(data, offset, size, mode);
        if (encoding != AudioFormat.ENCODING_PCM_16BIT || data == null || offset < 0 || size < 0 || offset > data.length - size || size % 2 != 0 || (mode != WRITE_BLOCKING && mode != WRITE_NON_BLOCKING)) return ERROR_BAD_VALUE;
        int accepted = 0;
        while (accepted < size) {
            int state = drain(mode); if (state <= 0) return accepted > 0 ? accepted : state;
            int count = Math.min(size - accepted, CAPACITY / 2) / 2;
            for (int i = 0; i < count * 2; i++) samples[i] = data[offset + accepted + i] / 32768f;
            convert(count); accepted += count * 2;
        }
        drain(mode); return accepted;
    }

    @Override public synchronized int write(float[] data, int offset, int size, int mode) {
        if (delegating || renderer == null) return super.write(data, offset, size, mode);
        if (encoding != AudioFormat.ENCODING_PCM_FLOAT || data == null || offset < 0 || size < 0 || offset > data.length - size || size % 2 != 0 || (mode != WRITE_BLOCKING && mode != WRITE_NON_BLOCKING)) return ERROR_BAD_VALUE;
        int accepted = 0;
        while (accepted < size) {
            int state = drain(mode); if (state <= 0) return accepted > 0 ? accepted : state;
            int count = Math.min(size - accepted, CAPACITY / 4) / 2;
            System.arraycopy(data, offset + accepted, samples, 0, count * 2);
            convert(count); accepted += count * 2;
        }
        drain(mode); return accepted;
    }

    @Override public synchronized int write(ByteBuffer data, int size, int mode) {
        if (delegating || renderer == null) return super.write(data, size, mode);
        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2, frameSize = bytes * 2;
        if (data == null || size < 0 || size > data.remaining() || size % frameSize != 0 || (mode != WRITE_BLOCKING && mode != WRITE_NON_BLOCKING)) return ERROR_BAD_VALUE;
        int accepted = 0;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (accepted < size) {
            int state = drain(mode); if (state <= 0) return accepted > 0 ? accepted : state;
            int count = Math.min(size - accepted, CAPACITY) / frameSize;
            for (int i = 0; i < count * 2; i++) samples[i] = bytes == 4 ? view.getFloat() : view.getShort() / 32768f;
            convert(count); accepted += count * frameSize; data.position(data.position() + count * frameSize);
        }
        drain(mode); return accepted;
    }

    @Override public synchronized void flush() {
        super.flush(); pendingOffset = pendingLength = 0;
        if (renderer != null) renderer.reset(); frames = 0;
    }
    @Override public synchronized void release() { renderer = null; pendingLength = 0; super.release(); }
}
