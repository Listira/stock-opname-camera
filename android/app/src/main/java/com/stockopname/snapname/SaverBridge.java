package com.stockopname.snapname;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves photos (JPEG) into Pictures/SnapName/<yyyy-MM-dd>, optionally embedding GPS into
 * EXIF; saves CSV logs into Documents/SnapName; opens a coordinate in a maps app.
 * Works fully offline (GPS coords are satellite-based; only viewing a map needs internet).
 */
public class SaverBridge {

    private final Context ctx;
    private android.webkit.WebView web;   // buat callback hasil save async ke JS

    SaverBridge(Context c) { this.ctx = c; }

    void setWebView(android.webkit.WebView w) { this.web = w; }

    private void runJs(final String js) {
        if (web == null || !(ctx instanceof Activity)) return;
        ((Activity) ctx).runOnUiThread(new Runnable() {
            @Override public void run() { web.evaluateJavascript(js, null); }
        });
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /**
     * Simpan foto TANPA nge-blok JS thread. savePhoto() sinkron bikin UI beku
     * selama decode+tulis+EXIF (bisa >1 detik di resolusi tinggi) — kerasa banget
     * kalau user save cepat berturut-turut (nama duplikat = ga perlu ngetik).
     * Hasil dikirim balik lewat window.__onSavedNative(cbId, uri) ('' = gagal).
     */
    @JavascriptInterface
    public void savePhotoAsync(final String dataUrl, final String name,
                               final String lat, final String lon, final String cbId) {
        new Thread(new Runnable() {
            @Override public void run() {
                String uri = saveInner(dataUrl, name, lat, lon, true);
                if (uri == null) uri = "";
                runJs("window.__onSavedNative && window.__onSavedNative('"
                        + cbId + "','" + uri.replace("\\", "\\\\").replace("'", "\\'") + "')");
            }
        }).start();
    }

    @JavascriptInterface
    public String savePhoto(String dataUrl, String name, String lat, String lon) {
        return saveInner(dataUrl, name, lat, lon, false);
    }

    private String saveInner(String dataUrl, String name, String lat, String lon, boolean quiet) {
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return saveBytes(bytes, name, lat, lon, quiet);
        } catch (Exception e) {
            toast("Gagal simpan: " + e.getMessage());
            return "";
        }
    }

    /** Inti simpan: terima JPEG bytes MENTAH (tanpa base64) -> tulis + EXIF + MediaStore. */
    String saveBytes(byte[] bytes, String name, String lat, String lon, boolean quiet) {
        try {
            String sub = "SnapName/" + today();

            boolean hasGeo = lat != null && !lat.isEmpty() && lon != null && !lon.isEmpty();
            double dlat = 0, dlon = 0;
            if (hasGeo) {
                try { dlat = Double.parseDouble(lat); dlon = Double.parseDouble(lon); }
                catch (Exception e) { hasGeo = false; }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + sub);
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) { toast("Gagal bikin file"); return ""; }
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                os.write(bytes); os.flush(); os.close();
                if (hasGeo) {
                    try {
                        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
                        ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                        exif.setLatLong(dlat, dlon);
                        exif.saveAttributes();
                        pfd.close();
                    } catch (Exception ex) { /* EXIF opsional */ }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
                if (!quiet) toast("Tersimpan ke Pictures/" + sub + (hasGeo ? "  📍" : ""));
                return uri.toString();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), sub);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes); fos.flush(); fos.close();
                if (hasGeo) {
                    try {
                        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
                        exif.setLatLong(dlat, dlon);
                        exif.saveAttributes();
                    } catch (Exception ex) { /* EXIF opsional */ }
                }
                if (!quiet) toast("Tersimpan ke Pictures/" + sub + (hasGeo ? "  📍" : ""));
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            toast("Gagal simpan: " + e.getMessage());
            return "";
        }
    }

    /** Sisa ruang penyimpanan internal (byte) — buat peringatan storage. */
    @JavascriptInterface
    public long freeBytes() {
        try {
            android.os.StatFs s = new android.os.StatFs(
                    Environment.getExternalStorageDirectory().getPath());
            return s.getAvailableBytes();
        } catch (Exception e) { return -1; }
    }

    /** Hapus foto dari penyimpanan (dipanggil dari galeri in-app). */
    @JavascriptInterface
    public void deletePhoto(String uriOrPath) {
        try {
            if (uriOrPath == null || uriOrPath.isEmpty()) return;
            if (uriOrPath.startsWith("content")) {
                ctx.getContentResolver().delete(Uri.parse(uriOrPath), null, null);
            } else {
                new File(uriOrPath).delete();
            }
        } catch (Exception e) { /* abaikan */ }
    }

    /** Simpan teks (CSV log) ke Documents/SnapName. */
    @JavascriptInterface
    public void saveText(String content, String name) {
        try {
            byte[] bytes = content.getBytes("UTF-8");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/SnapName");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) { toast("Gagal bikin CSV"); return; }
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                os.write(bytes); os.flush(); os.close();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SnapName");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes); fos.flush(); fos.close();
            }
            toast("CSV tersimpan ke Documents/SnapName");
        } catch (Exception e) {
            toast("Gagal simpan CSV: " + e.getMessage());
        }
    }

    /** Buka koordinat di aplikasi peta (Google Maps / browser). */
    @JavascriptInterface
    public void openMaps(String lat, String lon) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(Foto)"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(ctx.getPackageManager()) == null) {
                i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps?q=" + lat + "," + lon));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Exception e) {
            toast("Ga bisa buka Maps: " + e.getMessage());
        }
    }

    /* ================= SAVE PORT (localhost) =================
     * Jalur simpan TERCEPAT: JS kirim JPEG bytes MENTAH via HTTP POST ke
     * 127.0.0.1 -> zero base64, zero blok di JS thread, tulis file full di
     * thread server. Ini yang bikin save serasa instan (kaya kamera WA).
     * Amanin: bind loopback saja + token acak per sesi. */
    private java.net.ServerSocket srv;
    private int port = 0;
    private String token = "";
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    void startPort() {
        try {
            srv = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            port = srv.getLocalPort();
            token = java.util.UUID.randomUUID().toString().replace("-", "");
            Thread t = new Thread(new Runnable() { @Override public void run() { acceptLoop(); } });
            t.setDaemon(true);
            t.start();
        } catch (Exception e) { port = 0; }
    }

    void stopPort() {
        try { if (srv != null) srv.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            try {
                final java.net.Socket s = srv.accept();
                pool.execute(new Runnable() { @Override public void run() { handle(s); } });
            } catch (Exception e) { break; }
        }
    }

    private void handle(java.net.Socket s) {
        try {
            s.setSoTimeout(15000);
            java.io.InputStream in = s.getInputStream();
            java.io.OutputStream out = s.getOutputStream();
            // baca request line + headers (sampai \r\n\r\n)
            java.io.ByteArrayOutputStream hb = new java.io.ByteArrayOutputStream();
            int state = 0, b;
            while ((b = in.read()) != -1) {
                hb.write(b);
                if (b == '\r' && (state == 0 || state == 2)) state++;
                else if (b == '\n' && (state == 1 || state == 3)) state++;
                else state = 0;
                if (state == 4) break;
            }
            String head = hb.toString("UTF-8");
            String[] lines = head.split("\r\n");
            String req = lines.length > 0 ? lines[0] : "";
            int clen = 0;
            for (String l : lines) {
                if (l.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { clen = Integer.parseInt(l.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            if (req.startsWith("OPTIONS")) { respond(out, 204, "", null); s.close(); return; }
            String path = req.split(" ").length > 1 ? req.split(" ")[1] : "";
            java.util.HashMap<String, String> q = new java.util.HashMap<>();
            int qi = path.indexOf('?');
            if (qi >= 0) {
                for (String kv : path.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) q.put(kv.substring(0, eq),
                            java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
            if (!req.startsWith("POST") || !path.startsWith("/save") || !token.equals(q.get("tk")) || clen <= 0) {
                respond(out, 403, "{}", "application/json"); s.close(); return;
            }
            byte[] body = new byte[clen];
            int off = 0;
            while (off < clen) {
                int r = in.read(body, off, clen - off);
                if (r < 0) break;
                off += r;
            }
            String uri = saveBytes(body, q.get("name"), q.get("la"), q.get("lo"), true);
            if (uri == null) uri = "";
            String json = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            respond(out, 200, json, "application/json");
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void respond(java.io.OutputStream out, int code, String body, String type) throws Exception {
        byte[] bb = body.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + " OK\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: content-type\r\n"
                + (type != null ? "Content-Type: " + type + "\r\n" : "")
                + "Content-Length: " + bb.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(bb);
        out.flush();
    }

    /** JS ambil "port|token" buat jalur save cepat; "" kalau server gagal start. */
    @JavascriptInterface
    public String getSavePort() { return port > 0 ? (port + "|" + token) : ""; }

    private void toast(final String msg) {
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
