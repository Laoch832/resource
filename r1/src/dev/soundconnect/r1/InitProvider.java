package dev.soundconnect.r1;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public class InitProvider extends ContentProvider {
    public static final class Playback extends InitProvider {}
    public boolean onCreate() { ProfileStore.initialize(getContext()); return true; }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
