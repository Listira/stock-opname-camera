package com.stockopname.snapname;

import android.app.Activity;
import android.graphics.Matrix;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LifecycleOwner;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KAMERA NATIVE (CameraX) — jalur yang dipakai kamera bawaan / WhatsApp.
 *
 * Kenapa ada: WebView di banyak HP ga ngasih kontrol zoom optik ke halaman web,
 * jadi lensa tele/ultrawide ga akan pernah kepakai lewat getUserMedia. Di sini
 * kamera dipegang langsung sama Android: setZoomRatio() bikin sistem PINDAH LENSA
 * FISIK sendiri persis kaya kamera bawaan. Bonus: foto diambil dari ImageCapture
 * (resolusi & pemrosesan kamera asli), bukan dari frame preview.
 *
 * Preview digambar di PreviewView DI BELAKANG WebView yang dibikin transparan,
 * jadi seluruh UI (tombol, sheet nama, galeri) tetap HTML seperti sebelumnya.
 */
public class CamBridge {

    private final Activity act;
    private final PreviewView view;
    private final LifecycleOwner owner;
    private WebView web;

    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture capture;
    private boolean backFacing = true;
    private volatile boolean ready = false;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // hasil jepretan nunggu diambil JS lewat port lokal (id -> JPEG bytes)
    private final ConcurrentHashMap<String, byte[]> shots = new ConcurrentHashMap<>();
    private int shotSeq = 0;

    CamBridge(Activity a, PreviewView v, LifecycleOwner o) {
        this.act = a; this.view = v; this.owner = o;
    }

    void setWebView(WebView w) { this.web = w; }

    byte[] takeShot(String id) { return id == null ? null : shots.remove(id); }

    private void runJs(final String js) {
        if (web == null) return;
        act.runOnUiThread(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
    }

    /** Nyalain kamera. Balikin lewat window.__camReady(ok, zoomMin, zoomMax). */
    @JavascriptInterface
    public void start() {
        act.runOnUiThread(new Runnable() { @Override public void run() { bind(); } });
    }

    private void bind() {
        try {
            final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f =
                    ProcessCameraProvider.getInstance(act);
            f.addListener(new Runnable() {
                @Override public void run() {
                    try {
                        provider = f.get();
                        provider.unbindAll();

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(view.getSurfaceProvider());

                        capture = new ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setJpegQuality(92)
                                .build();

                        CameraSelector sel = backFacing
                                ? CameraSelector.DEFAULT_BACK_CAMERA
                                : CameraSelector.DEFAULT_FRONT_CAMERA;

                        // Pemindai QR/barcode jalan di jalur analisis terpisah — cuma di-bind
                        // kalau fiturnya DINYALAKAN user (kalau mati: nol beban & nol baterai).
                        analysis = null;
                        if (scanOn) {
                            analysis = new ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build();
                            analysis.setAnalyzer(io, new BarcodeAnalyzer());
                        }

                        camera = (analysis != null)
                                ? provider.bindToLifecycle(owner, sel, preview, capture, analysis)
                                : provider.bindToLifecycle(owner, sel, preview, capture);
                        ready = true;

                        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                        float mn = zs != null ? zs.getMinZoomRatio() : 1f;
                        float mx = zs != null ? zs.getMaxZoomRatio() : 1f;
                        runJs("window.__camReady && window.__camReady(true," + mn + "," + mx + ")");
                    } catch (Exception e) {
                        ready = false;
                        runJs("window.__camReady && window.__camReady(false,1,1)");
                    }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(act));
        } catch (Exception e) {
            runJs("window.__camReady && window.__camReady(false,1,1)");
        }
    }

    @JavascriptInterface
    public void stop() {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) {}
            ready = false;
        }});
    }

    @JavascriptInterface
    public boolean isReady() { return ready; }

    /* ================= SCAN QR / BARCODE ASET =================
     * Jalan realtime dari aliran kamera (ImageAnalysis + ML Kit yang modelnya
     * dibundel di APK -> OFFLINE penuh). Hasil terakhir disimpan di sini,
     * JS tinggal ambil pas mau dipakai. Kalau fitur dimatikan, analyzer sama
     * sekali ga di-bind: nol pemakaian CPU/baterai. */
    private ImageAnalysis analysis;
    private volatile boolean scanOn = false;
    private volatile String lastCode = "";
    private volatile long lastCodeAt = 0;

    @JavascriptInterface
    public void setScan(final boolean on) {
        if (scanOn == on) return;
        scanOn = on;
        if (!on) { lastCode = ""; lastCodeAt = 0; }
        act.runOnUiThread(new Runnable() { @Override public void run() { if (ready) bind(); } });
    }

    @JavascriptInterface
    public boolean isScanOn() { return scanOn; }

    /** Kode terakhir yang kebaca, format "kode|umurMillisecond"; "" kalau belum ada. */
    @JavascriptInterface
    public String lastScan() {
        if (lastCode == null || lastCode.isEmpty()) return "";
        return lastCode + "|" + (System.currentTimeMillis() - lastCodeAt);
    }

    @JavascriptInterface
    public void clearScan() { lastCode = ""; lastCodeAt = 0; }

    private class BarcodeAnalyzer implements ImageAnalysis.Analyzer {
        private final com.google.mlkit.vision.barcode.BarcodeScanner sc =
                com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
                        new com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(
                                        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
                                        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128,
                                        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_39,
                                        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                                        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX)
                                .build());

        @Override public void analyze(final ImageProxy proxy) {
            android.media.Image mi = proxy.getImage();
            if (mi == null || !scanOn) { proxy.close(); return; }
            com.google.mlkit.vision.common.InputImage in =
                    com.google.mlkit.vision.common.InputImage.fromMediaImage(
                            mi, proxy.getImageInfo().getRotationDegrees());
            sc.process(in)
              .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<java.util.List<com.google.mlkit.vision.barcode.common.Barcode>>() {
                  @Override public void onSuccess(java.util.List<com.google.mlkit.vision.barcode.common.Barcode> list) {
                      if (list != null && !list.isEmpty()) {
                          String v = list.get(0).getRawValue();
                          if (v != null && !v.trim().isEmpty()) {
                              v = v.trim();
                              boolean baru = !v.equals(lastCode);
                              lastCode = v; lastCodeAt = System.currentTimeMillis();
                              if (baru) runJs("window.__onScan && window.__onScan("
                                      + jsStr(v) + ")");
                          }
                      }
                      proxy.close();
                  }
              })
              .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                  @Override public void onFailure(Exception e) { proxy.close(); }
              });
        }
    }

    private static String jsStr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                      .replace("\n", " ").replace("\r", " ") + "'";
    }

    /** Zoom OPTIK: di HP multi-lensa, sistem yang nuker lensa fisik sendiri. */
    @JavascriptInterface
    public void setZoom(final float ratio) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { if (camera != null) camera.getCameraControl().setZoomRatio(ratio); } catch (Exception ignored) {}
        }});
    }

    /** "min|max|current" — buat bikin chip lensa & indikator zoom. */
    @JavascriptInterface
    public String zoomRange() {
        try {
            ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
            if (zs == null) return "1|1|1";
            return zs.getMinZoomRatio() + "|" + zs.getMaxZoomRatio() + "|" + zs.getZoomRatio();
        } catch (Exception e) { return "1|1|1"; }
    }

    @JavascriptInterface
    public void focusAt(final float x, final float y) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try {
                MeteringPoint p = view.getMeteringPointFactory()
                        .createPoint(x * view.getWidth(), y * view.getHeight());
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build());
            } catch (Exception ignored) {}
        }});
    }

    @JavascriptInterface
    public boolean hasTorch() {
        try { return camera != null && camera.getCameraInfo().hasFlashUnit(); } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public void torch(final boolean on) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { if (camera != null) camera.getCameraControl().enableTorch(on); } catch (Exception ignored) {}
        }});
    }

    @JavascriptInterface
    public void flip() {
        act.runOnUiThread(new Runnable() { @Override public void run() { backFacing = !backFacing; bind(); }});
    }

    @JavascriptInterface
    public boolean isBack() { return backFacing; }

    /**
     * Jepret pakai ImageCapture (kualitas kamera asli, bukan frame preview).
     * JPEG-nya disimpan sebentar di memori; JS ambil lewat GET /shot?id=...
     * lalu tinggal ditempel stempel GPS + watermark seperti biasa.
     */
    @JavascriptInterface
    public void capture() {
        if (!ready || capture == null) { runJs("window.__camShot && window.__camShot('','kamera belum siap')"); return; }
        final String id = "sh" + (++shotSeq) + "_" + System.nanoTime();
        capture.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(ImageProxy image) {
                try {
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    int rot = image.getImageInfo().getRotationDegrees();
                    shots.put(id, bytes);
                    runJs("window.__camShot && window.__camShot('" + id + "','',"
                            + rot + "," + image.getWidth() + "," + image.getHeight() + ")");
                } catch (Exception e) {
                    runJs("window.__camShot && window.__camShot('','" + esc(e.getMessage()) + "')");
                } finally { image.close(); }
            }
            @Override public void onError(ImageCaptureException e) {
                runJs("window.__camShot && window.__camShot('','" + esc(e.getMessage()) + "')");
            }
        });
    }

    private static String esc(String s) {
        return String.valueOf(s).replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }
}
