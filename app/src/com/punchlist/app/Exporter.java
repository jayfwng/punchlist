package com.punchlist.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 엑셀/사진 내보내기 (기기 저장 또는 공유용 ZIP) */
public class Exporter {

    public interface Listener {
        void progress(String msg, int pct);
    }

    public static class Result {
        public final List<String> saved = new ArrayList<String>();
        public File shareFile;
        public String shareMime;
    }

    private final Context ctx;
    private final File photoDir;
    private final Listener listener;

    public Exporter(Context ctx, File photoDir, Listener listener) {
        this.ctx = ctx;
        this.photoDir = photoDir;
        this.listener = listener;
    }

    private static class Row {
        XlsxWriter.Item item;
        String catName, createdAt;
    }

    public Result run(JSONObject opt) throws Exception {
        JSONObject proj = opt.getJSONObject("project");
        String building = proj.optString("name", "");
        String date = proj.optString("date", "");
        String inspector = proj.optString("inspector", "");
        boolean excel = opt.optBoolean("excel", true);
        String photoMode = opt.optString("photoMode", "none"); // with | without | both | none
        boolean share = "share".equals(opt.optString("target", "save"));

        List<XlsxWriter.Category> cats = new ArrayList<XlsxWriter.Category>();
        List<Row> rows = new ArrayList<Row>();
        JSONArray ca = opt.getJSONArray("cats");
        for (int i = 0; i < ca.length(); i++) {
            JSONObject c = ca.getJSONObject(i);
            XlsxWriter.Category cat = new XlsxWriter.Category(c.getString("name"), c.getString("title"), c.getString("summary"));
            JSONArray items = c.getJSONArray("items");
            for (int j = 0; j < items.length(); j++) {
                JSONObject o = items.getJSONObject(j);
                XlsxWriter.Item it = new XlsxWriter.Item();
                it.no = o.optInt("no", j + 1);
                it.floor = o.optString("floor", "");
                it.location = o.optString("location", "");
                it.equipment = o.optString("equipment", "");
                it.issue = o.optString("issue", "");
                it.action = o.optString("action", "");
                it.company = o.optString("company", "");
                it.actionDate = o.optString("actionDate", "");
                it.done = o.optBoolean("done", false);
                JSONArray ph = o.optJSONArray("photos");
                if (ph != null) for (int k = 0; k < ph.length(); k++) it.photoIds.add(ph.getString(k));
                cat.items.add(it);
                Row r = new Row();
                r.item = it;
                r.catName = cat.name;
                r.createdAt = o.optString("createdAt", "");
                rows.add(r);
            }
            cats.add(cat);
        }

        String stamp = new SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(new Date());
        String safeB = safe(building.length() > 0 ? building : "현장");
        String excelName = safeB + "_Punch List_" + stamp + ".xlsx";
        String folder = safeB + "_" + stamp.substring(0, 6);
        Result res = new Result();

        int totalPhotos = 0;
        if (!"none".equals(photoMode)) for (Row r : rows) totalPhotos += r.item.photoIds.size() * ("both".equals(photoMode) ? 2 : 1);

        ZipOutputStream zip = null;
        File zipFile = null;
        if (share && (!"none".equals(photoMode))) {
            File d = new File(ctx.getCacheDir(), "share");
            clearDir(d);
            d.mkdirs();
            zipFile = new File(d, folder + "_PunchList.zip");
            zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), 1 << 16));
        }

        try {
            // ---------- 엑셀
            if (excel) {
                listener.progress("엑셀 생성 중…", 0);
                XlsxWriter w = new XlsxWriter(building, date, inspector, cats);
                XlsxWriter.ImageSource src = new XlsxWriter.ImageSource() {
                    @Override public XlsxWriter.Img load(String id) throws IOException {
                        return PhotoUtil.loadForExcel(new File(photoDir, id + ".jpg"));
                    }
                };
                XlsxWriter.Progress pr = new XlsxWriter.Progress() {
                    @Override public void onPhoto(int done, int total) {
                        listener.progress("엑셀 사진 삽입 " + done + "/" + total, total == 0 ? 0 : done * 50 / total);
                    }
                };
                if (zip != null) {
                    zip.putNextEntry(new ZipEntry(excelName));
                    w.write(new NoCloseStream(zip), src, pr);
                    zip.closeEntry();
                } else if (share) {
                    File d = new File(ctx.getCacheDir(), "share");
                    clearDir(d);
                    d.mkdirs();
                    File f = new File(d, excelName);
                    OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
                    try { w.write(os, src, pr); } finally { os.close(); }
                    res.shareFile = f;
                    res.shareMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                } else {
                    OutputStream os = openOutput(Environment.DIRECTORY_DOWNLOADS, "PunchList", excelName,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", res);
                    try { w.write(new BufferedOutputStream(os, 1 << 16), src, pr); } finally { closePending(os); }
                }
            }

            // ---------- 사진
            if (!"none".equals(photoMode)) {
                int done = 0;
                Set<String> used = new HashSet<String>();
                for (Row r : rows) {
                    XlsxWriter.Item it = r.item;
                    for (int k = 0; k < it.photoIds.size(); k++) {
                        File f = new File(photoDir, it.photoIds.get(k) + ".jpg");
                        if (!f.exists()) continue;
                        String base = String.format(Locale.KOREA, "%03d_%s_%s", it.no, safe(it.floor), safe(it.location));
                        if (it.photoIds.size() > 1) base += "_" + (k + 1);
                        String[] modes = "both".equals(photoMode) ? new String[]{"with", "without"} : new String[]{photoMode};
                        for (String m : modes) {
                            String sub = r.catName + ("both".equals(photoMode) ? ("/" + ("with".equals(m) ? "워터마크" : "원본")) : "");
                            String name = base + ".jpg";
                            int dup = 2;
                            while (used.contains(sub + "/" + name)) name = base + "(" + (dup++) + ").jpg";
                            used.add(sub + "/" + name);
                            byte[] data;
                            if ("with".equals(m)) {
                                PhotoUtil.Watermark wm = new PhotoUtil.Watermark();
                                wm.building = building; wm.floor = it.floor; wm.location = it.location;
                                wm.category = r.catName; wm.equipment = it.equipment; wm.issue = it.issue;
                                wm.date = r.createdAt; wm.no = it.no;
                                data = PhotoUtil.watermarked(f, wm);
                            } else {
                                data = readAll(f);
                            }
                            done++;
                            listener.progress("사진 저장 " + done + "/" + totalPhotos, (excel ? 50 : 0) + done * (excel ? 50 : 100) / Math.max(1, totalPhotos));
                            if (zip != null) {
                                zip.putNextEntry(new ZipEntry("사진/" + sub + "/" + name));
                                zip.write(data);
                                zip.closeEntry();
                            } else {
                                OutputStream os = openOutput(Environment.DIRECTORY_PICTURES, "PunchList/" + folder + "/" + sub, name, "image/jpeg", null);
                                try { os.write(data); } finally { closePending(os); }
                            }
                        }
                    }
                }
                if (zip == null && done > 0) res.saved.add("사진 " + done + "장 → 사진(Pictures)/PunchList/" + folder);
                if (zip == null && done == 0) res.saved.add("저장할 사진이 없습니다");
            }
        } finally {
            if (zip != null) zip.close();
        }

        if (zipFile != null) {
            res.shareFile = zipFile;
            res.shareMime = "application/zip";
        }
        listener.progress("완료", 100);
        return res;
    }

    // ------------------------------------------------------------------ 저장소

    /** MediaStore(안드10+) 또는 공용 폴더(안드9 이하)에 파일 출력 스트림 생성 */
    private OutputStream openOutput(String publicDir, String subPath, String name, String mime, Result res) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put("relative_path", publicDir + "/" + subPath);
            v.put("is_pending", 1);
            Uri coll = Environment.DIRECTORY_PICTURES.equals(publicDir)
                    ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : Uri.parse("content://media/external/downloads");
            Uri uri = cr.insert(coll, v);
            if (uri == null) throw new IOException("파일을 만들 수 없습니다: " + name);
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) throw new IOException("파일을 열 수 없습니다: " + name);
            if (res != null) res.saved.add("엑셀 → 다운로드(Download)/" + subPath + "/" + name);
            return new PendingStream(os, uri, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(publicDir), subPath);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("폴더를 만들 수 없습니다: " + dir);
            File f = new File(dir, name);
            if (res != null) res.saved.add("엑셀 → " + f.getAbsolutePath());
            return new PendingStream(new FileOutputStream(f), null, f);
        }
    }

    private void closePending(OutputStream os) throws IOException {
        os.close();
        PendingStream ps = (PendingStream) (os instanceof PendingStream ? os : null);
        if (ps == null) return;
        if (ps.uri != null) {
            ContentValues v = new ContentValues();
            v.put("is_pending", 0);
            ctx.getContentResolver().update(ps.uri, v, null, null);
        } else if (ps.file != null) {
            MediaScannerConnection.scanFile(ctx, new String[]{ps.file.getAbsolutePath()}, null, null);
        }
    }

    private static class PendingStream extends OutputStream {
        final OutputStream out; final Uri uri; final File file;
        boolean closed;
        PendingStream(OutputStream out, Uri uri, File file) { this.out = out; this.uri = uri; this.file = file; }
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { if (!closed) { closed = true; out.flush(); out.close(); } }
    }

    /** ZipOutputStream 을 닫지 않도록 감싸기 (XlsxWriter 내부 zip 의 finish 는 허용) */
    private static class NoCloseStream extends OutputStream {
        final OutputStream out;
        NoCloseStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() { }
    }

    static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        InputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return b;
    }

    static void clearDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    static String safe(String s) {
        if (s == null) return "";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_").trim();
        if (r.length() > 40) r = r.substring(0, 40);
        return r;
    }
}
