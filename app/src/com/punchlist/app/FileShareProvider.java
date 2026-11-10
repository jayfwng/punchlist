package com.punchlist.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 카메라 촬영 결과 저장(cam/) 및 공유(share/) 용 최소 ContentProvider.
 * content://com.punchlist.app.files/{cam|share}/{파일명} -> cacheDir/{cam|share}/{파일명}
 */
public class FileShareProvider extends ContentProvider {
    public static final String AUTHORITY = "com.punchlist.app.files";

    public static Uri uriFor(String dir, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(dir).appendPath(name).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 2) throw new FileNotFoundException(uri.toString());
        String dir = uri.getPathSegments().get(0);
        String name = uri.getPathSegments().get(1);
        if (!("cam".equals(dir) || "share".equals(dir)) || name.contains("/") || name.contains("..")) throw new FileNotFoundException(uri.toString());
        File d = new File(getContext().getCacheDir(), dir);
        d.mkdirs();
        return new File(d, name);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m;
        if (mode.contains("w")) {
            m = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
            if (mode.contains("t") || !mode.contains("a")) m |= ParcelFileDescriptor.MODE_TRUNCATE;
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            m = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] cols = new String[projection.length];
        Object[] vals = new Object[projection.length];
        int n = 0;
        for (String c : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(c)) { cols[n] = c; vals[n++] = f.getName(); }
            else if (OpenableColumns.SIZE.equals(c)) { cols[n] = c; vals[n++] = f.length(); }
        }
        String[] c2 = new String[n]; Object[] v2 = new Object[n];
        System.arraycopy(cols, 0, c2, 0, n); System.arraycopy(vals, 0, v2, 0, n);
        MatrixCursor mc = new MatrixCursor(c2, 1);
        mc.addRow(v2);
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "application/octet-stream";
        n = n.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
