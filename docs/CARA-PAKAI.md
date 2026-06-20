# Snap & Name — Cara Pakai (OFFLINE / tanpa sinyal)

App buat stock opname: **jepret aset → langsung muncul kotak nama → simpan**.
Foto kesimpen ke folder **Download** HP dengan nama yang lo ketik. `.jpg`

> ⚠️ App ini TIDAK butuh internet sama sekali setelah ada di HP.
> Aman dipakai di kebun sawit / mode pesawat / sinyal nol.

---

## CARA A — Taruh file langsung di HP (paling anti-sinyal, RECOMMENDED)

Tidak perlu internet sama sekali, bahkan saat masang.

1. Colok HP ke laptop pakai kabel USB → pilih **"Transfer File / MTP"** di HP.
2. Copy **seluruh folder `stock-opname-cam`** ini ke memori HP
   (taruh di `Download` atau `Documents`, bebas).
3. Di HP, buka aplikasi **File Manager** → masuk folder tadi → ketuk **`index.html`**.
   - Kalau ditanya buka pakai apa, pilih browser apapun (Chrome/Firefox/Samsung Internet).
4. Izinkan **akses kamera** saat diminta → kelar. Tinggal jepret.

> Untuk gampang dibuka tiap hari: pas `index.html` kebuka, banyak browser
> punya menu **"Tambah ke layar Utama / Add to Home screen"**. Pakai itu,
> nanti ada ikonnya di home kayak app beneran, ga usah cari file lagi.

---

## CARA B — Install jadi app (PWA) lewat WiFi sekali aja

Sekali install di rumah/kantor (ada WiFi), habis itu selamanya offline.

1. Di laptop, masuk folder ini, jalankan server lokal sederhana
   (butuh Python — biasanya udah ada):
   ```
   python -m http.server 8080
   ```
2. Pastikan HP & laptop satu WiFi. Cek IP laptop (mis. `192.168.1.10`).
3. Di HP buka browser → ketik `http://192.168.1.10:8080`
4. Muncul tawaran **"Install / Tambah ke layar Utama"** → install.
5. Selesai. Cabut WiFi, masuk mode pesawat — **app tetap jalan** karena
   semua udah ke-cache di HP.

---

## Fitur

- 📸 **Jepret** → langsung muncul kotak nama (keyboard auto naik)
- ⌨️ **Ketik bebas** nama filenya, tekan Enter / tombol Simpan
- 🔁 Nama otomatis di-saranin & nomor urut naik sendiri (001 → 002 ...)
- 🏷️ Tombol **Aa** buat set awalan otomatis (mis. nama gudang/tanggal)
- 🔦 Tombol senter (kalau HP dukung) — berguna di gudang gelap
- 🔄 Ganti kamera depan/belakang
- 🧮 Penghitung jumlah foto per sesi
- ↩ Tombol "Ulang" kalau hasil foto jelek

## Catatan
- Foto masuk ke folder **Download**. Rapihin/pindah ke folder per-tanggal kalau perlu.
- Karakter ilegal nama file (`/ \ : * ? " < > |`) otomatis diganti `-`.
- Semua jalan offline. Ga ada data yang dikirim kemana-mana.
