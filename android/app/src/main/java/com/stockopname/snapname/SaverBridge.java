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

    SaverBridge(Context c) { this.ctx = c; }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    @JavascriptInterface
    public String savePhoto(String dataUrl, String name, String lat, String lon) {
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
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
                toast("Tersimpan ke Pictures/" + sub + (hasGeo ? "  📍" : ""));
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
                toast("Tersimpan ke Pictures/" + sub + (hasGeo ? "  📍" : ""));
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
