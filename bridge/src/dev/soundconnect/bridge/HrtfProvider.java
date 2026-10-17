package dev.soundconnect.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only; manifest restricts access to applications signed with the same key. */
public final class HrtfProvider extends ContentProvider {
    public boolean onCreate() { return true; }

    private File profile(Uri uri) throws FileNotFoundException {
        if (!SourceBridge.PROFILE_URI.equals(uri.toString())) throw new FileNotFoundException("Unknown profile URI");
        File root = getContext().getFilesDir();
        File file = new File(root, "multi_hrtf/backupfile_brir_h13");
        if (!file.isFile()) file = new File(root, "hrtf");
        if (!file.isFile()) throw new FileNotFoundException("请先在 Sound Connect 完成耳形分析。");
        if (file.length() == 0 || file.length() > ProfileInfo.MAX_BYTES) throw new FileNotFoundException("Invalid profile size");
        return file;
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(profile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = profile(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
            cursor.addRow(new Object[]{file.getName(), file.length()});
            return cursor;
        } catch (FileNotFoundException e) { return new MatrixCursor(new String[]{"_display_name", "_size"}); }
    }

    public String getType(Uri uri) { return "application/octet-stream"; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
