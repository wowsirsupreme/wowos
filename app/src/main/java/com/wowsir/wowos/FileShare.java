package com.wowsir.wowos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Hands files to other apps (video players, the APK installer) as content:// links.
 * Read-only, not exported; access is granted per link only.
 */
public class FileShare extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(authority(c))
                .encodedPath(Uri.encode(f.getAbsolutePath(), "/")).build();
    }

    static String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        if (ext.equals("mkv")) return "video/x-matroska";
        if (ext.equals("ts")) return "video/mp2t";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    /** Only files on shared storage or USB disks may be handed out. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException("No path");
        File f = new File(path);
        try {
            String canon = f.getCanonicalPath();
            String ext = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (canon.startsWith("/storage/") || canon.startsWith("/mnt/") || canon.startsWith(ext + "/")) {
                return f;
            }
        } catch (IOException ignored) { }
        throw new FileNotFoundException("Not allowed: " + path);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        try {
            return mimeOf(resolve(uri));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
