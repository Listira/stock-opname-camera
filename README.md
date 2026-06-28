# 📸 Snap & Name — Stock Opname Camera

A zero-dependency camera app that names each photo **the moment you take it**, built for
asset stock-taking (*stock opname*) on palm-oil plantations where **there is no internet
signal at all**.

> **The core idea:** snap → a name box pops up instantly → type → save. No more digging
> through a camera roll of `IMG_20260620_0001.jpg` files and guessing which asset is which.

🌐 **Live demo:** https://listira.github.io/stock-opname-camera/ *(open on a phone — it uses your camera)*
📱 **Android APK (fully offline):** [SnapName.apk](SnapName.apk)

---

## The problem this solves

During stock opname in the field, every asset must be photographed and documented. The
usual workaround — sending each photo to yourself on WhatsApp to "name" it — is slow and
unreliable, and out in the plantation **there is no signal**. Any cloud or web-based tool
is dead on arrival.

The requirements were brutally specific:

- **100% offline.** Must run in airplane mode, no server, no network calls — ever.
- **Name-on-capture.** The naming prompt must appear immediately after the shutter so files
  never get mixed up.
- **Free & lightweight.** No paid automation apps (the original idea was Tasker).

## The interesting engineering: one codebase, two runtimes

The app is a single vanilla-JS web app, but getting a **browser camera to work offline** is
the hard part — Chrome blocks `getUserMedia` on any non-secure origin, so opening the file
from a file manager (`file://`) silently disables the camera.

The same `index.html` therefore runs in **two modes**:

| Runtime | How the camera is allowed | How photos are saved | Limit |
|---|---|---|---|
| **Web demo** (GitHub Pages, HTTPS) | HTTPS is a secure context | `<a download>` blob to Downloads | 10/day (demo) |
| **Offline Android APK** | Native `WebView` + `WebViewAssetLoader` serving assets over the secure `https://appassets.androidplatform.net` origin, with `onPermissionRequest` granting the camera | JS→native bridge writes JPEG into `Pictures/SnapName` via `MediaStore` (WebView can't download `blob:` URLs) | unlimited |

The app detects its own environment at load time: if the native `AndroidSaver` bridge is
present it runs as the full offline app; on `*.github.io` it runs as a capped demo.

## Features

- 📸 Tap shutter → name sheet opens **inside the shutter gesture** so the mobile keyboard
  pops up automatically (a subtle but critical mobile UX detail)
- ⌨️ Free-text naming with auto-increment of trailing numbers (`Aset-001` → `Aset-002`)
- 🛡️ **Anti-duplicate guard** — repeated names auto-suffix `-2`, `-3` (O(1) via a counter map
  + a `Set` for correctness), so files never silently overwrite
- 🧮 Daily session counter that resets at midnight (persisted in `localStorage`)
- 🔍 **Pinch-to-zoom & tap-to-focus** (v2.0) — uses native `MediaStreamTrack` constraints
  (`zoom`, `pointsOfInterest`, `focusMode`) and **falls back to digital (canvas-crop) zoom**
  on devices that don't expose a zoom capability (e.g. MIUI/Redmi), so zoom works everywhere
- 🏷️ **Logo watermark** (v2.1) — drop in a company logo placed top-right on every photo, with
  a one-tap **automatic background removal** (corner-sampled chroma keying) for logos on
  plain backgrounds
- 📍 **GPS + date/time stamp** (v2.2) — a GPS-Map-Camera-style banner burned into each photo.
  **GPS coordinates and timestamp work fully offline** (satellite, no signal needed); the
  street address is reverse-geocoded best-effort only when online. Native geolocation bridged
  into the WebView
- 🗂️ **Auto folder per day** (v2.2) — photos land in `Pictures/SnapName/YYYY-MM-DD/`
- 📋 **CSV photo log** (v2.2) — every shot is logged (filename, time, lat/long, address) and
  exportable to a CSV for audit/rekap
- 🗺️ **EXIF geotagging + tap-to-Maps** (v2.3) — GPS coordinates are written into each photo's
  EXIF (toggleable) so the file opens to a map in Google Photos/gallery; the in-app preview
  has a "Lihat di Maps" link that fires a native `geo:` intent. Coordinates are captured at
  shutter time and persist with the photo
- 🖼️ **In-app session gallery** (v2.5) — a last-photo thumbnail (with a count badge) sits
  bottom-left like a real camera app; tap it to review this session's shots in a grid +
  lightbox without leaving for the system gallery (thumbnails cached in `localStorage`)
- 🔦 Torch toggle (when supported), front/back camera flip
- 🧼 Filename sanitisation (illegal characters, length cap for Android filesystems)
- 📴 Works in airplane mode; installable to the home screen as a PWA

## Testing

Behaviour is covered by **50+ automated tests** driven by Playwright with a fake camera
device — no manual clicking required.

- [`qa_test.py`](qa_test.py) — 31 functional checks (capture flow, sanitisation, dedupe,
  daily counter, demo limit, PWA assets, service worker…)
- [`stress_test.py`](stress_test.py) — 21 stress checks: 250-shot burst (heap-leak watch),
  200× duplicate-name storm, pathological names (2000 chars / emoji / CJK / reserved words),
  a shutter-then-instant-save **race condition**, and a 30× camera-restart storm.

```bash
pip install playwright && playwright install chromium
python qa_test.py
python stress_test.py
```

## Tech stack

- **Frontend:** vanilla HTML/CSS/JS (no framework, no build step), PWA (manifest + service worker)
- **Camera/capture:** `getUserMedia`, `<canvas>` capture, `toBlob`/`toDataURL`
- **Android wrapper:** Java, `WebView`, `androidx.webkit` `WebViewAssetLoader`, `MediaStore`
  (source in [`android/`](android/))
- **Tests:** Python + Playwright

## Build the APK

The Android wrapper source lives in [`android/`](android/). With a JDK 17 + Android SDK
(platform 34, build-tools 34):

```bash
cd android
gradle assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

## Repo layout

```
index.html, sw.js, manifest.json, icons/   # the web app (PWA / GitHub Pages root)
android/                                    # native WebView wrapper (offline camera + save bridge)
qa_test.py, stress_test.py                  # automated test suites
SnapName.apk                                # pre-built debug APK
docs/                                       # Indonesian usage & install guides
```

## License

[MIT](LICENSE)
