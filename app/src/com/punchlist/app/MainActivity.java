package com.punchlist.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOST = "app.local";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_CAMERA = 11, REQ_GALLERY = 12, REQ_STORAGE = 13, REQ_BACKUP = 14;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService photoExec = Executors.newSingleThreadExecutor();
    private final ExecutorService exportExec = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();

    private File photoDir, thumbDir, dataFile, pendingFile;
    private String camFileName, camReqId, galleryReqId;
    private String pendingExport;
    private volatile boolean exporting;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        photoDir = new File(getFilesDir(), "photos");
        thumbDir = new File(getFilesDir(), "thumbs");
        photoDir.mkdirs();
        thumbDir.mkdirs();
        dataFile = new File(getFilesDir(), "data.json");
        pendingFile = new File(getFilesDir(), "pending_photos.json");
        if (saved != null) {
            camFileName = saved.getString("camFile");
            camReqId = saved.getString("camReq");
            galleryReqId = saved.getString("galReq");
        }

        Window w = getWindow();
        w.setStatusBarColor(0xFF1E293B);
        w.setNavigationBarColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= 26) w.getDecorView().setSystemUiVisibility(0x10 /* LIGHT_NAVIGATION_BAR */);

        web = new WebView(this);
        web.setBackgroundColor(0xFFF1F5F9);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new LocalClient());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);
        web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("camFile", camFileName);
        out.putString("camReq", camReqId);
        out.putString("galReq", galleryReqId);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack ? window.onBack() : false)", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        photoExec.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 로컬 리소스 제공

    private class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (path.contains("..")) return notFound();
            try {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Cache-Control", "no-store");
                if (path.startsWith("/photos/")) return file(new File(photoDir, path.substring(8)), headers);
                if (path.startsWith("/thumbs/")) return file(new File(thumbDir, path.substring(8)), headers);
                if (path.startsWith("/preview/")) return file(new File(new File(getCacheDir(), "preview"), path.substring(9)), headers);
                InputStream in = getAssets().open("www" + path);
                return new WebResourceResponse(mime(path), "UTF-8", 200, "OK", headers, in);
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !HOST.equals(Uri.parse(url).getHost());
        }
    }

    private WebResourceResponse file(File f, Map<String, String> headers) throws IOException {
        if (!f.exists()) return notFound();
        return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new FileInputStream(f));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<String, String>(),
                new java.io.ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg")) return "image/jpeg";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ JS 호출

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript(code, null); }
        });
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    // ------------------------------------------------------------------ 사진 처리

    private String newPhotoId() {
        return "p" + System.currentTimeMillis() + "_" + Integer.toHexString(0x1000 + rnd.nextInt(0xefff));
    }

    /** 처리 완료된 사진을 pending 파일에 쌓고 JS 에 알림 (앱이 재시작돼도 유실 방지) */
    private synchronized void addPending(String reqId, String id, int w, int h) {
        try {
            JSONArray arr = readPendingArr();
            JSONObject o = new JSONObject();
            o.put("req", reqId == null ? "" : reqId);
            o.put("id", id);
            o.put("w", w);
            o.put("h", h);
            arr.put(o);
            writeText(pendingFile, arr.toString());
        } catch (Exception e) {
            toast("사진 등록 오류: " + e.getMessage());
        }
        js("window.onNativePhotos && window.onNativePhotos()");
    }

    private JSONArray readPendingArr() {
        try {
            if (pendingFile.exists()) return new JSONArray(readText(pendingFile));
        } catch (Exception ignored) { }
        return new JSONArray();
    }

    private void processAsync(final File src, final String reqId, final boolean deleteSrc) {
        js("window.onPhotoBusy && window.onPhotoBusy(true)");
        photoExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    String id = newPhotoId();
                    int[] wh = PhotoUtil.processNew(src, new File(photoDir, id + ".jpg"), new File(thumbDir, id + ".jpg"));
                    addPending(reqId, id, wh[0], wh[1]);
                } catch (Throwable e) {
                    toast("사진 처리 실패: " + e.getMessage());
                } finally {
                    if (deleteSrc) src.delete();
                    js("window.onPhotoBusy && window.onPhotoBusy(false)");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            File f = camFileName == null ? null : new File(new File(getCacheDir(), "cam"), camFileName);
            String req = camReqId;
            camFileName = null;
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                processAsync(f, req, true);
            } else if (resultCode == RESULT_OK && data != null && data.getExtras() != null && data.getExtras().get("data") instanceof Bitmap) {
                // 일부 카메라 앱은 EXTRA_OUTPUT 을 무시하고 작은 썸네일만 돌려줌
                try {
                    Bitmap bm = (Bitmap) data.getExtras().get("data");
                    File tmp = new File(getCacheDir(), "cam_small.jpg");
                    PhotoUtil.writeJpeg(bm, tmp, 95);
                    processAsync(tmp, req, true);
                    toast("카메라 앱이 저해상도 사진만 전달했습니다");
                } catch (IOException e) {
                    toast("사진 저장 실패");
                }
            } else if (f != null) {
                f.delete();
            }
        } else if (requestCode == REQ_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importBackup(data.getData());
            else js("window.onBackupImported && window.onBackupImported(null)");
        } else if (requestCode == REQ_GALLERY) {
            String req = galleryReqId;
            if (resultCode != RESULT_OK || data == null) return;
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) importUri(cd.getItemAt(i).getUri(), req);
            } else if (data.getData() != null) {
                importUri(data.getData(), req);
            }
        }
    }

    private void importUri(final Uri uri, final String req) {
        if (uri == null) return;
        photoExec.execute(new Runnable() {
            @Override public void run() {
                File tmp = new File(getCacheDir(), "import_" + System.nanoTime() + ".jpg");
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("열 수 없음");
                    OutputStream out = new FileOutputStream(tmp);
                    try { copy(in, out); } finally { in.close(); out.close(); }
                    processAsync(tmp, req, true);
                } catch (Exception e) {
                    tmp.delete();
                    toast("사진 가져오기 실패: " + e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ 내보내기

    private void startExport(final String json) {
        if (Build.VERSION.SDK_INT < 29) {
            try {
                boolean needsStorage = !"share".equals(new JSONObject(json).optString("target"));
                if (needsStorage && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingExport = json;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                    return;
                }
            } catch (Exception ignored) { }
        }
        if (exporting) { toast("이미 내보내는 중입니다"); return; }
        exporting = true;
        exportExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject opt = new JSONObject(json);
                    Exporter ex = new Exporter(MainActivity.this, photoDir, new Exporter.Listener() {
                        @Override public void progress(String msg, int pct) {
                            js("window.onExportProgress && window.onExportProgress(" + q(msg) + "," + pct + ")");
                        }
                    });
                    final Exporter.Result r = opt.has("backup")
                            ? ex.backup(opt.getString("backup"), "share".equals(opt.optString("target")))
                            : ex.run(opt);
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("saved", new JSONArray(r.saved));
                    res.put("shared", r.shareFile != null);
                    js("window.onExportDone && window.onExportDone(" + res.toString() + ")");
                    if (r.shareFile != null) {
                        ui.post(new Runnable() {
                            @Override public void run() { shareFile(r.shareFile, r.shareMime); }
                        });
                    }
                } catch (Throwable e) {
                    try {
                        JSONObject res = new JSONObject();
                        res.put("ok", false);
                        res.put("error", String.valueOf(e.getMessage() == null ? e.toString() : e.getMessage()));
                        js("window.onExportDone && window.onExportDone(" + res.toString() + ")");
                    } catch (Exception ignored) { }
                } finally {
                    exporting = false;
                }
            }
        });
    }

    private void shareFile(File f, String mime) {
        Uri uri = FileShareProvider.uriFor("share", f.getName());
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        send.setClipData(ClipData.newRawUri(f.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "공유하기"));
        } catch (ActivityNotFoundException e) {
            toast("공유할 앱이 없습니다");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE) {
            String p = pendingExport;
            pendingExport = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && p != null) startExport(p);
            else js("window.onExportDone && window.onExportDone({ok:false,error:'저장 권한이 필요합니다'})");
        }
    }

    // ------------------------------------------------------------------ 백업 불러오기

    private void importBackup(final Uri uri) {
        js("window.onPhotoBusy && window.onPhotoBusy(true)");
        exportExec.execute(new Runnable() {
            @Override public void run() {
                String dataJson = null;
                int n = 0;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("파일을 열 수 없습니다");
                    java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in));
                    try {
                        java.util.zip.ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            String name = e.getName();
                            if ("data.json".equals(name)) {
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                copy(zin, bo);
                                dataJson = bo.toString("UTF-8");
                            } else if (name.startsWith("photos/") && name.endsWith(".jpg")) {
                                String fn = name.substring(7);
                                if (fn.contains("/") || fn.contains("..")) continue;
                                File out = new File(photoDir, fn);
                                OutputStream os = new FileOutputStream(out);
                                try { copy(zin, os); } finally { os.close(); }
                                try {
                                    android.graphics.Bitmap t = PhotoUtil.scaleToEdge(PhotoUtil.decodeSampled(out.getAbsolutePath(), PhotoUtil.THUMB_EDGE), PhotoUtil.THUMB_EDGE);
                                    if (t != null) { PhotoUtil.writeJpeg(t, new File(thumbDir, fn), 75); t.recycle(); }
                                } catch (Exception ignored) { }
                                n++;
                            }
                        }
                    } finally {
                        zin.close();
                    }
                    if (dataJson == null) throw new IOException("펀치리스트 백업 파일이 아닙니다 (data.json 없음)");
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("data", dataJson);
                    o.put("photos", n);
                    js("window.onBackupImported && window.onBackupImported(" + o.toString() + ")");
                } catch (Exception e) {
                    js("window.onBackupImported && window.onBackupImported({ok:false,error:" + q(e.getMessage()) + "})");
                } finally {
                    js("window.onPhotoBusy && window.onPhotoBusy(false)");
                }
            }
        });
    }

    private static void deleteChildren(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ------------------------------------------------------------------ 파일 유틸

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    static String readText(File f) throws IOException {
        return new String(Exporter.readAll(f), "UTF-8");
    }

    static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(s.getBytes("UTF-8"));
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    // ------------------------------------------------------------------ JS 브리지

    private class Bridge {
        @JavascriptInterface
        public String loadData() {
            try {
                if (dataFile.exists()) return readText(dataFile);
                File bak = new File(getFilesDir(), "data.json.bak");
                if (bak.exists()) return readText(bak);
            } catch (Exception e) {
                toast("데이터 읽기 오류: " + e.getMessage());
            }
            return "";
        }

        @JavascriptInterface
        public boolean saveData(String json) {
            synchronized (MainActivity.this) {
                try {
                    if (dataFile.exists() && dataFile.length() > 2) {
                        File bak = new File(getFilesDir(), "data.json.bak");
                        bak.delete();
                        dataFile.renameTo(bak);
                    }
                    writeText(dataFile, json);
                    return true;
                } catch (Exception e) {
                    toast("저장 실패: " + e.getMessage());
                    return false;
                }
            }
        }

        @JavascriptInterface
        public void takePhoto(final String reqId) {
            ui.post(new Runnable() {
                @Override public void run() {
                    File dir = new File(getCacheDir(), "cam");
                    dir.mkdirs();
                    camFileName = "cam_" + System.currentTimeMillis() + ".jpg";
                    camReqId = reqId;
                    Uri uri = FileShareProvider.uriFor("cam", camFileName);
                    Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    it.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                    it.setClipData(ClipData.newRawUri("photo", uri));
                    it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivityForResult(it, REQ_CAMERA);
                    } catch (ActivityNotFoundException e) {
                        camFileName = null;
                        toast("카메라 앱을 찾을 수 없습니다");
                    } catch (SecurityException e) {
                        camFileName = null;
                        toast("카메라를 열 수 없습니다: " + e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickPhotos(final String reqId) {
            ui.post(new Runnable() {
                @Override public void run() {
                    galleryReqId = reqId;
                    Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                    it.setType("image/*");
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(Intent.createChooser(it, "사진 선택"), REQ_GALLERY);
                    } catch (ActivityNotFoundException e) {
                        toast("갤러리 앱을 찾을 수 없습니다");
                    }
                }
            });
        }

        /** 처리 완료된 사진 목록을 가져가고 비움 */
        @JavascriptInterface
        public String takePendingPhotos() {
            synchronized (MainActivity.this) {
                JSONArray arr = readPendingArr();
                pendingFile.delete();
                return arr.toString();
            }
        }

        @JavascriptInterface
        public void deletePhoto(String id) {
            if (id == null || id.contains("/") || id.contains("..")) return;
            new File(photoDir, id + ".jpg").delete();
            new File(thumbDir, id + ".jpg").delete();
        }

        @JavascriptInterface
        public void exportProject(String json) {
            final String j = json;
            ui.post(new Runnable() {
                @Override public void run() { startExport(j); }
            });
        }

        /** 워터마크 미리보기 이미지를 만들고 URL 반환 */
        @JavascriptInterface
        public String watermarkPreview(String json) {
            try {
                JSONObject o = new JSONObject(json);
                File src = new File(photoDir, o.getString("photoId") + ".jpg");
                if (!src.exists()) return "";
                PhotoUtil.Watermark w = new PhotoUtil.Watermark();
                w.building = o.optString("building");
                w.floor = o.optString("floor");
                w.location = o.optString("location");
                w.category = o.optString("category");
                w.equipment = o.optString("equipment");
                w.issue = o.optString("issue");
                w.date = o.optString("date");
                w.no = o.optInt("no");
                File dir = new File(getCacheDir(), "preview");
                dir.mkdirs();
                Exporter.clearDir(dir);
                String name = "wm_" + System.currentTimeMillis() + ".jpg";
                FileOutputStream fo = new FileOutputStream(new File(dir, name));
                try { fo.write(PhotoUtil.watermarked(src, w)); } finally { fo.close(); }
                return "https://" + HOST + "/preview/" + name;
            } catch (Exception e) {
                return "";
            }
        }

        /** 저장 공간 사용량 (MB) */
        @JavascriptInterface
        public String storageInfo() {
            long total = 0;
            int n = 0;
            File[] fs = photoDir.listFiles();
            if (fs != null) for (File f : fs) { total += f.length(); n++; }
            return "{\"photos\":" + n + ",\"mb\":" + (Math.round(total / 104857.6) / 10.0) + "}";
        }

        @JavascriptInterface
        public void exportBackup(String dataJson, String target) {
            try {
                JSONObject o = new JSONObject();
                o.put("backup", dataJson);
                o.put("target", target);
                final String j = o.toString();
                ui.post(new Runnable() {
                    @Override public void run() { startExport(j); }
                });
            } catch (Exception e) {
                toast("백업 실패: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void importBackup() {
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                    it.setType("*/*");
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(Intent.createChooser(it, "백업 ZIP 파일 선택"), REQ_BACKUP);
                    } catch (ActivityNotFoundException e) {
                        toast("파일 선택 앱을 찾을 수 없습니다");
                    }
                }
            });
        }

        /** 목록에 없는 사진 파일 정리 */
        @JavascriptInterface
        public void cleanupPhotos(String keepJson) {
            try {
                JSONArray a = new JSONArray(keepJson);
                java.util.HashSet<String> keep = new java.util.HashSet<String>();
                for (int i = 0; i < a.length(); i++) keep.add(a.getString(i) + ".jpg");
                for (File dir : new File[]{photoDir, thumbDir}) {
                    File[] fs = dir.listFiles();
                    if (fs != null) for (File f : fs) if (!keep.contains(f.getName())) f.delete();
                }
            } catch (Exception ignored) { }
        }

        /** 모든 데이터와 사진 삭제 */
        @JavascriptInterface
        public void resetAll() {
            synchronized (MainActivity.this) {
                deleteChildren(photoDir);
                deleteChildren(thumbDir);
                dataFile.delete();
                new File(getFilesDir(), "data.json.bak").delete();
                pendingFile.delete();
            }
        }

        @JavascriptInterface
        public void toast(String msg) { MainActivity.this.toast(msg); }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }
    }
}
