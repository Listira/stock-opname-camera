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

/**
 * Saves a JPEG (passed as a data: URL from the web app) into Pictures/SnapName,
 * so it shows up in the gallery. Works fully offline.
 */
public class SaverBridge {

    private final Context ctx;

    SaverBridge(Context c) {
        this.ctx = c;
    }

    @JavascriptInterface
    public void savePhoto(String dataUrl, String name) {
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SnapName");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) { toast("Gagal bikin file"); return; }
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                os.write(bytes);
                os.flush();
                os.close();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "SnapName");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.flush();
                fos.close();
            }
            toast("Tersimpan ke Pictures/SnapName");
        } catch (Exception e) {
            toast("Gagal simpan: " + e.getMessage());
        }
    }

    private void toast(final String msg) {
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
