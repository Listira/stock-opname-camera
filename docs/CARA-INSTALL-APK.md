# Snap & Name — Install APK ke HP

File APK: **`SnapName.apk`** (ada di Desktop & folder ini). Ukuran ±0.44 MB.

## Cara masang (sekali doang)

1. **Pindahin `SnapName.apk` ke HP** — colok USB & copy, atau kirim via Bluetooth/share.
2. Di HP, buka **File Manager** → ketuk **`SnapName.apk`**.
3. Muncul peringatan "demi keamanan, blokir dari sumber tidak dikenal" →
   ketuk **Setelan** → aktifkan **"Izinkan dari sumber ini"** → kembali → **Install**.
   (Ini wajar karena APK ga dari Play Store. Aman, ini app lo sendiri.)
4. Buka app **"Snap & Name"** dari home screen.
5. Pertama kali jalan, muncul **"Izinkan Snap & Name mengambil gambar?"** → **Allow**.
6. Kamera nyala. Tinggal jepret → namain → simpan. **Sepenuhnya offline.**

## Hasil foto

Tersimpan di folder **`Pictures/SnapName`** (kelihatan di Galeri), pakai nama yang lo ketik.

## Kenapa ini jalan offline (beda dari Chrome)

Di APK, app-nya sendiri yang minta izin kamera ke Android (kayak app kamera biasa),
jadi GA kena aturan "secure context" Chrome yang ngeblok `file://`. 100% jalan di
mode pesawat / kebun tanpa sinyal.

---

## Buat developer (kalau mau build ulang nanti)

Toolchain portable ada di `C:\Users\chryz\android-build\` (JDK, Android SDK, Gradle).
Source project: `C:\Users\chryz\android-build\SnapName\`.

Build ulang (mis. setelah ubah index.html):
```powershell
# 1. copy ulang web app ke assets
Copy-Item C:\Users\chryz\stock-opname-cam\index.html `
  C:\Users\chryz\android-build\SnapName\app\src\main\assets\ -Force

# 2. build
$env:JAVA_HOME = Get-Content C:\Users\chryz\android-build\JAVA_HOME.txt
$env:ANDROID_HOME = "C:\Users\chryz\android-build\sdk"
cd C:\Users\chryz\android-build\SnapName
& C:\Users\chryz\android-build\gradle\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon

# APK keluar di: app\build\outputs\apk\debug\app-debug.apk
```

Catatan: ini **debug APK** (ditandatangani debug key) — cocok buat pemakaian pribadi.
Kalau mau sebar ke banyak orang / Play Store, perlu di-build `release` + signing key sendiri.
