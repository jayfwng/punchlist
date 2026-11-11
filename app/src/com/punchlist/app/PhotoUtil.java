package com.punchlist.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 사진 축소/회전/워터마크 처리 */
public final class PhotoUtil {
    private PhotoUtil() { }

    /** 앱 보관용 사진: 긴 변 1920px, JPEG 88 (엑셀 첨부 사진보다 훨씬 선명, 원본보다 가벼움) */
    public static final int STORE_EDGE = 1920;
    public static final int STORE_QUALITY = 88;
    public static final int THUMB_EDGE = 400;
    /** 엑셀 삽입 화질: high 1024px/q82, std 800px/q75 (기본·추천), low 640px/q70 */
    public static int[] excelPreset(String q) {
        if ("high".equals(q)) return new int[]{1024, 82};
        if ("low".equals(q)) return new int[]{640, 70};
        return new int[]{800, 75};
    }

    public static class Watermark {
        public String building = "", floor = "", location = "", category = "", equipment = "", issue = "", date = "";
        public int no;
    }

    /** src(원본) -> dst(보관용), thumb(목록용). @return {width, height} */
    public static int[] processNew(File src, File dst, File thumb) throws IOException {
        Bitmap bm = decodeOriented(src, STORE_EDGE);
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다");
        try {
            writeJpeg(bm, dst, STORE_QUALITY);
            Bitmap t = scaleToEdge(bm, THUMB_EDGE);
            writeJpeg(t, thumb, 75);
            if (t != bm) t.recycle();
            return new int[]{bm.getWidth(), bm.getHeight()};
        } finally {
            bm.recycle();
        }
    }

    /** 엑셀용 축소 JPEG */
    public static XlsxWriter.Img loadForExcel(File f, int edge, int quality) throws IOException {
        if (!f.exists()) return null;
        Bitmap bm = decodeSampled(f.getAbsolutePath(), edge);
        if (bm == null) return null;
        Bitmap s = scaleToEdge(bm, edge);
        if (s != bm) bm.recycle();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
        s.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        XlsxWriter.Img img = new XlsxWriter.Img(bo.toByteArray(), s.getWidth(), s.getHeight());
        s.recycle();
        return img;
    }

    /** 워터마크를 넣은 JPEG 바이트 */
    public static byte[] watermarked(File f, Watermark w) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다: " + f.getName());
        if (!bm.isMutable()) {
            Bitmap c = bm.copy(Bitmap.Config.ARGB_8888, true);
            bm.recycle();
            bm = c;
        }
        drawWatermark(bm, w);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(512 * 1024);
        bm.compress(Bitmap.CompressFormat.JPEG, STORE_QUALITY, bo);
        bm.recycle();
        return bo.toByteArray();
    }

    static void drawWatermark(Bitmap bm, Watermark w) {
        int W = bm.getWidth(), H = bm.getHeight();
        int base = Math.min(W, H);
        float big = Math.max(18f, base / 22f);
        float mid = big * 0.82f;
        float small = big * 0.66f;
        float pad = big * 0.6f;
        int textW = (int) (W - pad * 2);
        Canvas cv = new Canvas(bm);

        TextPaint p1 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p1.setColor(Color.WHITE);
        p1.setTextSize(big);
        p1.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p1.setShadowLayer(big / 8f, 0, 0, Color.BLACK);

        TextPaint p2 = new TextPaint(p1);
        p2.setTextSize(mid);
        p2.setTypeface(Typeface.DEFAULT);
        p2.setColor(Color.rgb(255, 236, 140));

        TextPaint p3 = new TextPaint(p2);
        p3.setTextSize(small);
        p3.setColor(Color.rgb(220, 220, 220));

        StringBuilder l1 = new StringBuilder();
        appendSep(l1, w.building);
        appendSep(l1, w.floor);
        appendSep(l1, w.location);

        StringBuilder l2 = new StringBuilder();
        if (w.category.length() > 0) l2.append('[').append(w.category).append("] ");
        if (w.equipment.length() > 0) l2.append(w.equipment).append(" - ");
        l2.append(w.issue.replace('\n', ' '));

        String l3 = (w.no > 0 ? "No." + w.no + "   " : "") + w.date;

        StaticLayout s1 = new StaticLayout(l1.toString(), p1, textW, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0, false);
        StaticLayout s2 = clamp(l2.toString(), p2, textW, 3);
        StaticLayout s3 = new StaticLayout(l3, p3, textW, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0, false);

        float gap = big * 0.25f;
        float bandH = pad + s1.getHeight() + gap + s2.getHeight() + gap + s3.getHeight() + pad * 0.8f;
        float top = H - bandH;
        Paint bg = new Paint();
        bg.setColor(Color.argb(150, 0, 0, 0));
        cv.drawRect(0, top, W, H, bg);
        Paint accent = new Paint();
        accent.setColor(Color.rgb(255, 152, 0));
        cv.drawRect(0, top, Math.max(6, big / 4f), H, accent);

        cv.save();
        cv.translate(pad, top + pad);
        s1.draw(cv);
        cv.translate(0, s1.getHeight() + gap);
        s2.draw(cv);
        cv.translate(0, s2.getHeight() + gap);
        s3.draw(cv);
        cv.restore();
    }

    private static StaticLayout clamp(String text, TextPaint p, int width, int maxLines) {
        StaticLayout l = new StaticLayout(text, p, width, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0, false);
        if (l.getLineCount() <= maxLines) return l;
        int end = l.getLineEnd(maxLines - 1);
        String t = text.substring(0, Math.max(0, end - 2)) + "…";
        return new StaticLayout(t, p, width, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0, false);
    }

    private static void appendSep(StringBuilder sb, String s) {
        if (s == null || s.trim().length() == 0) return;
        if (sb.length() > 0) sb.append("  |  ");
        sb.append(s.trim());
    }

    // ------------------------------------------------------------------

    static Bitmap decodeOriented(File f, int maxEdge) throws IOException {
        Bitmap bm = decodeSampled(f.getAbsolutePath(), maxEdge);
        if (bm == null) return null;
        int deg = 0;
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        Bitmap s = scaleToEdge(bm, maxEdge);
        if (s != bm) bm.recycle();
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, true);
            if (r != s) s.recycle();
            s = r;
        }
        return s;
    }

    static Bitmap decodeSampled(String path, int maxEdge) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int longEdge = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o2);
    }

    static Bitmap scaleToEdge(Bitmap bm, int maxEdge) {
        int w = bm.getWidth(), h = bm.getHeight();
        int le = Math.max(w, h);
        if (le <= maxEdge) return bm;
        float s = maxEdge / (float) le;
        return Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    static void writeJpeg(Bitmap bm, File f, int q) throws IOException {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            bm.compress(Bitmap.CompressFormat.JPEG, q, fo);
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("저장 실패: " + f.getName());
    }
}
