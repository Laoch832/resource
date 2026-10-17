package dev.soundconnect.r1;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Independent reader for the Sony RIFF/BRIR h13 HKI2 coefficient container.
 * Only ear-filter coefficient data is handled; this class has no network or media-DRM API.
 */
public final class HkiProfile {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public final int sampleRate;
    public final int taps;
    public final Direction[] directions;

    public static final class Direction {
        public final int azimuth, polar;
        public final float[][] filters;
        Direction(int azimuth, int polar, float[][] filters) {
            this.azimuth = azimuth; this.polar = polar; this.filters = filters;
        }
    }

    private HkiProfile(int rate, int taps, Direction[] directions) {
        this.sampleRate = rate; this.taps = taps; this.directions = directions;
    }

    private static int i32(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8)
                | ((data[offset + 2] & 255) << 16) | (data[offset + 3] << 24);
    }
    private static boolean magic(byte[] data, int offset, String value) {
        if (offset < 0 || offset + value.length() > data.length) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != value.charAt(i)) return false;
        return true;
    }

    public static HkiProfile parse(byte[] input) throws IOException {
        if (input.length == 0 || input.length > MAX_BYTES) throw new IOException("Invalid profile size");
        if (!magic(input,0,"RIFF") && !magic(input,0,"hki2")) {
            byte[] decoded=decodeMime(input);
            if (!magic(decoded,0,"RIFF") && !magic(decoded,0,"hki2")) throw new IOException("Unsupported coefficient container");
            return parse(decoded);
        }
        byte[] hki = input;
        if (magic(input, 0, "RIFF")) {
            if (!magic(input, 8, "BRIR") || input.length < 20 || (i32(input, 4) & 0xffffffffL) + 8 != input.length)
                throw new IOException("Invalid RIFF/BRIR envelope");
            int found = -1, foundLength = 0;
            long cursor = 12;
            while (cursor + 8 <= input.length) {
                int offset = (int) cursor;
                long size = i32(input, offset + 4) & 0xffffffffL;
                long end = cursor + 8 + size;
                if (end > input.length) throw new IOException("Truncated RIFF chunk");
                if (magic(input, offset, "h13 ")) {
                    if (found != -1) throw new IOException("Ambiguous h13 chunks");
                    found = offset + 8; foundLength = (int) size;
                }
                cursor = end + (size & 1);
            }
            if (cursor != input.length || found < 0) throw new IOException("Missing or malformed h13 chunk");
            hki = Arrays.copyOfRange(input, found, found + foundLength);
        }
        if (hki.length < 160 || !magic(hki, 0, "hki2") || (hki.length - 144) % 16 != 0)
            throw new IOException("Unsupported coefficient container (requires HKI2)");
        int rate = i32(hki, 56), taps = i32(hki, 88), count = i32(hki, 92);
        if (rate < 8000 || rate > 192000 || taps < 1 || taps > 65536 || count < 1 || count > 32 || i32(hki, 60) != 2)
            throw new IOException("Unsupported coefficient dimensions");
        byte[] clear;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] key = deriveKey(i32(hki, 4), hki[78] & 255);
            try {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(hki, 8, 16));
                clear = cipher.doFinal(hki, 144, hki.length - 144);
            } finally { Arrays.fill(key, (byte) 0); }
        } catch (GeneralSecurityException e) { throw new IOException("HKI2 coefficient decoding failed", e); }
        try {
        long expected = (long) count * 2 * (16L + 4L * taps);
        if (expected != clear.length) throw new IOException("Coefficient payload dimensions do not match");
        ByteBuffer buffer = ByteBuffer.wrap(clear).order(ByteOrder.LITTLE_ENDIAN);
        Direction[] directions = new Direction[count];
        for (int d = 0; d < count; d++) {
            int az = -1, polar = -1;
            float[][] filters = new float[2][taps];
            for (int ear = 0; ear < 2; ear++) {
                int a = buffer.getInt(), p = buffer.getInt(), channels = buffer.getInt(), e = buffer.getInt();
                if (a < 0 || a > 360 || p < 0 || p > 180 || channels != 2 || e != ear
                        || (ear == 1 && (az != a || polar != p))) throw new IOException("Invalid directional record");
                az = a; polar = p;
                for (int t = 0; t < taps; t++) {
                    float value = buffer.getFloat();
                    if (Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > 64)
                        throw new IOException("Invalid impulse response coefficient");
                    filters[ear][t] = value;
                }
            }
            directions[d] = new Direction(az, polar, filters);
        }
        return new HkiProfile(rate, taps, directions);
        } finally { Arrays.fill(clear, (byte) 0); }
    }

    /** Sony's legacy profile store may contain MIME Base64 rather than raw HKI2 bytes. */
    private static byte[] decodeMime(byte[] encoded) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(encoded.length*3/4);
        int[] q=new int[4];int count=0;boolean finished=false;
        for(byte b:encoded) {
            int c=b&255;if(c==32 || c==9 || c==10 || c==13)continue;
            if(finished)throw new IOException("Trailing Base64 profile data");
            int value=c>='A' && c<='Z'?c-'A':c>='a' && c<='z'?c-'a'+26:c>='0' && c<='9'?c-'0'+52:c=='+'?62:c=='/'?63:c=='='?-2:-1;
            if(value==-1)throw new IOException("Unsupported coefficient encoding");
            q[count++]=value;
            if(count==4) {
                if(q[0]<0 || q[1]<0 || (q[2]==-2 && q[3]!=-2))throw new IOException("Invalid Base64 padding");
                out.write((q[0]<<2)|(q[1]>>4));
                if(q[2]==-2) {if((q[1]&15)!=0)throw new IOException("Invalid Base64 bits");finished=true;}
                else {
                    out.write((q[1]<<4)|(q[2]>>2));
                    if(q[3]==-2){if((q[2]&3)!=0)throw new IOException("Invalid Base64 bits");finished=true;}
                    else out.write((q[2]<<6)|q[3]);
                }
                count=0;
            }
        }
        if(count!=0 || out.size()==0)throw new IOException("Incomplete Base64 profile");
        return out.toByteArray();
    }

    static byte[] deriveKey(int identifier, int formatVersion) throws GeneralSecurityException, IOException {
        if (identifier == 0) throw new IOException("Missing HKI2 format identifier");
        if (identifier != (int) 0xd93de477L) {
            byte[] seed = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(identifier).array();
            return MessageDigest.getInstance("MD5").digest(seed);
        }
        if (formatVersion != 1) throw new IOException("Unsupported HKI2 format version");
        int mix = HkiFormatParameters.FORMAT_MIX;
        int[] state = {mix & 255, (mix >>> 8) & 255, (mix >>> 16) & 255, (mix >>> 24) & 255};
        byte[] result = new byte[16];
        int seed = identifier;
        for (int i = 0; i < 16; i++) {
            int value = seed >> 8;
            for (int j = 0; j < 4; j++) value += i + j + (j + 1) * (i + 1) * state[j] + 2;
            result[i] = (byte) value;
            for (int j = 0; j < 4; j++) state[j] = (state[(j + 1) & 3] ^ seed) & 255;
            seed = value;
        }
        for (int i = 0; i < 16; i += 4) {
            byte a = result[i], b = result[i + 1];
            result[i] = result[i + 3]; result[i + 1] = result[i + 2]; result[i + 2] = b; result[i + 3] = a;
        }
        Arrays.fill(state, 0);
        return result;
    }

    public Direction nearest(int azimuth, int polar) {
        Direction nearest = null;
        double score = Double.POSITIVE_INFINITY;
        for (Direction d : directions) {
            int delta = Math.abs(d.azimuth - azimuth) % 360;
            delta = Math.min(delta, 360 - delta);
            double distance = (double) delta * delta + (double) (d.polar - polar) * (d.polar - polar);
            if (distance < score) { score = distance; nearest = d; }
        }
        return nearest;
    }
}
