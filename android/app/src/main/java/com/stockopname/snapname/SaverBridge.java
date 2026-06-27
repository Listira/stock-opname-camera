package com.stockopname.snapname;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves photos (JPEG) into Pictures/SnapName/<yyyy-MM-dd> and CSV logs into
 * Documents/SnapName. Works fully offline.
 */
public class SaverBridge {

    private final Context ctx;

    SaverBridge(Context c) { this.ctx = c; }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    @JavascriptInterface
    public void savePhoto(String dataUrl, String name) {
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            String sub = "SnapName/" + today();   // folder otomatis per tanggal

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + sub);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) { toast("Gagal bikin file"); return; }
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                os.write(bytes); os.flush(); os.close();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), sub);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes); fos.flush(); fos.close();
            }
            toast("Tersimpan ke Pictures/" + sub);
        } catch (Exception e) {
            toast("Gagal simpan: " + e.getMessage());
        }
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
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOCUMENTS + "/SnapName");
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
