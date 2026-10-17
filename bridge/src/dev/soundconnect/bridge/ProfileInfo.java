package dev.soundconnect.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded opaque-profile import. A recognized container is not a supported renderer. */
public final class ProfileInfo {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private ProfileInfo() {}

    public static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (length == 0) continue;
            if (length > MAX_BYTES - output.size()) throw new IOException("参数文件超过 8 MiB 限制");
            output.write(buffer, 0, length);
        }
        if (output.size() == 0) throw new IOException("参数文件为空");
        return output.toByteArray();
    }

    public static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static String container(byte[] data) {
        if (starts(data, "RIFF") && data.length >= 12 && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') return "RIFF/WAVE 容器（未解析）";
        if (data.length >= 8 && data[0] == (byte) 0x89 && startsAt(data, 1, "HDF")
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10) return "HDF5 容器（未解析）";
        return "Sony 参数数据（格式未确定）";
    }

    private static boolean starts(byte[] data, String text) { return startsAt(data, 0, text); }
    private static boolean startsAt(byte[] data, int start, String text) {
        if (data.length < start + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (data[start + i] != text.charAt(i)) return false;
        return true;
    }
}
