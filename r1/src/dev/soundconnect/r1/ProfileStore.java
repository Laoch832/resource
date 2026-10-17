package dev.soundconnect.r1;

import android.content.Context;
import java.io.*;

/** Profile storage belongs to the music app and is never sent over the network. */
public final class ProfileStore {
    private static volatile Context application;
    private ProfileStore() {}
    public static void initialize(Context context) { application = context.getApplicationContext(); }
    public static File directory() throws IOException {
        Context context = application;
        if (context == null) {
            try {
                context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (context != null) initialize(context);
            } catch (Exception ignored) {}
        }
        if (context == null) throw new IOException("Application context unavailable");
        return context.getFilesDir();
    }
    public static HkiProfile read() throws IOException {
        File file = new File(directory(), "sony-360ra-profile.bin");
        if (!file.isFile() || file.length() < 1 || file.length() > HkiProfile.MAX_BYTES) throw new IOException("Missing ear profile");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int)file.length());
        InputStream stream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384]; int n;
            while ((n = stream.read(buffer)) != -1) {
                if ((long)bytes.size() + n > HkiProfile.MAX_BYTES) throw new IOException("Ear profile too large");
                bytes.write(buffer, 0, n);
            }
        } finally { stream.close(); }
        return HkiProfile.parse(bytes.toByteArray());
    }
    public static boolean enabled() {
        try { return new File(directory(), "sony-r1-enabled").isFile(); }
        catch (IOException e) { return false; }
    }
    public static void enable(boolean enabled) throws IOException {
        File flag = new File(directory(), "sony-r1-enabled");
        if (enabled) {
            read();
            FileOutputStream stream = new FileOutputStream(flag);
            try { stream.write(1); stream.getFD().sync(); } finally { stream.close(); }
        } else if (flag.exists() && !flag.delete()) throw new IOException("Cannot disable renderer");
    }
}
