import sys, time, http.server, socketserver, threading, os, functools
sys.stdout.reconfigure(encoding="utf-8")

PORT = 8077
ROOT = os.path.dirname(os.path.abspath(__file__))

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
httpd = socketserver.TCPServer(("127.0.0.1", PORT), Handler)
httpd.RequestHandlerClass.log_message = lambda *a, **k: None  # quiet
threading.Thread(target=httpd.serve_forever, daemon=True).start()

from playwright.sync_api import sync_playwright

results = []
def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("  PASS " if cond else "  FAIL ") + name + ((" -> "+str(detail)) if detail else ""))

console_errs, page_errs = [], []

def shoot(page):
    page.click("#shutter")
    page.wait_for_selector("#sheet.open", timeout=3000)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, args=[
        "--use-fake-device-for-media-stream",
        "--use-fake-ui-for-media-stream",
        "--autoplay-policy=no-user-gesture-required",
    ])
    ctx = browser.new_context(accept_downloads=True, viewport={"width":412,"height":860},
                              permissions=["camera","geolocation"])
    ctx.set_geolocation({"latitude":-6.264199,"longitude":106.866546,"accuracy":8})
    page = ctx.new_page()
    page.on("console", lambda m: console_errs.append(m.text) if m.type=="error" else None)
    page.on("pageerror", lambda e: page_errs.append(str(e)))

    print("\n=== LOAD ===")
    page.goto(f"http://127.0.0.1:{PORT}/index.html")
    check("page load (title)", "Snap" in page.title())
    check("start overlay visible", page.is_visible("#start"))

    print("\n=== START CAMERA ===")
    page.click("#startBtn")
    ok=False; vw=0
    for _ in range(50):
        vw = page.evaluate("document.querySelector('#video').videoWidth")
        if vw and vw>0: ok=True; break
        time.sleep(0.1)
    check("camera started (videoWidth>0)", ok, f"videoWidth={vw}")
    check("start overlay hidden after start", not page.is_visible("#start"))

    print("\n=== V2: TAP-TO-FOCUS + ZOOM (graceful on fake cam) ===")
    page.mouse.click(206, 430)  # tap middle of live preview -> focus ring
    time.sleep(0.15)
    check("tap shows focus ring", "show" in (page.get_attribute("#focusRing","class") or ""))
    # fake cam has no native zoom cap -> must fall back to DIGITAL zoom (CSS scale)
    check("digital zoom mode on fake cam", page.evaluate("zoomMode")=="digital", page.evaluate("zoomMode"))
    page.evaluate("setZoom(3)")
    check("digital zoom applies CSS scale", "scale(3" in page.evaluate("document.querySelector('#video').style.transform"),
          page.evaluate("document.querySelector('#video').style.transform"))
    page.evaluate("setZoom(1)")

    print("\n=== SHUTTER #1 -> NAME SHEET ===")
    shoot(page)
    check("name sheet opened", "open" in page.get_attribute("#sheet","class"))
    # preview is set in the async toBlob callback (a few ms after sheet opens) -> poll
    blob_ok=False
    for _ in range(30):
        if page.evaluate("getComputedStyle(document.querySelector('#sheetPreview')).backgroundImage.includes('blob:')"):
            blob_ok=True; break
        time.sleep(0.05)
    check("preview shows captured blob", blob_ok)
    check("suggestion chips rendered", page.eval_on_selector_all(".chip","e=>e.length")>0)
    check("name input auto-focused",
          page.evaluate("document.activeElement && document.activeElement.id==='nameInput'"))

    print("\n=== TYPE NAME + SAVE (sanitize) ===")
    page.fill("#nameInput", "Laptop Acer/Gudang:A")  # spaces + illegal chars
    with page.expect_download(timeout=4000) as di:
        page.click("#saveBtn")
    fname = di.value.suggested_filename
    check("download triggered on save", bool(fname), fname)
    check("filename sanitized", fname=="Laptop-Acer-Gudang-A.jpg", fname)
    check("sheet closed after save", "open" not in page.get_attribute("#sheet","class"))
    check("counter == 1", page.inner_text("#count")=="1", page.inner_text("#count"))

    print("\n=== AUTO-INCREMENT SUGGESTION ===")
    shoot(page)
    page.fill("#nameInput","Aset-007")
    with page.expect_download() as d: page.click("#saveBtn")
    d.value
    shoot(page)
    sug = page.input_value("#nameInput")
    check("auto-increment trailing number (007->008)", sug=="Aset-008", sug)
    page.click("#retakeBtn")

    print("\n=== ANTI-DUPLICATE GUARD ===")
    shoot(page); page.fill("#nameInput","DUPL")
    with page.expect_download() as d1: page.click("#saveBtn")
    n1=d1.value.suggested_filename
    shoot(page); page.fill("#nameInput","DUPL")
    with page.expect_download() as d2: page.click("#saveBtn")
    n2=d2.value.suggested_filename
    check("dup name auto-suffixed -2", n1=="DUPL.jpg" and n2=="DUPL-2.jpg", f"{n1} / {n2}")

    print("\n=== CHIP APPEND ===")
    shoot(page)
    page.fill("#nameInput","")
    page.click(".chip >> nth=0")
    val = page.input_value("#nameInput")
    check("chip appends to name", len(val)>0, val)
    page.click("#retakeBtn")

    print("\n=== EMPTY NAME GUARD ===")
    shoot(page)
    page.fill("#nameInput","   ")
    page.click("#saveBtn")
    time.sleep(0.3)
    check("whitespace-only name blocked", "open" in page.get_attribute("#sheet","class"))
    page.click("#retakeBtn")

    print("\n=== RETAKE ===")
    shoot(page)
    page.click("#retakeBtn")
    check("retake closes sheet", "open" not in page.get_attribute("#sheet","class"))

    print("\n=== PREFIX FEATURE REMOVED ===")
    check("prefix button gone", page.eval_on_selector_all("#prefixBtn","e=>e.length")==0)
    check("no prompt() leftover for prefix", page.evaluate("localStorage.getItem('so_prefix')")==None)

    print("\n=== COUNTER PERSISTS PER-DAY ===")
    cnt_before = int(page.inner_text("#count"))
    page.reload()
    page.click("#startBtn")
    for _ in range(50):
        if page.evaluate("document.querySelector('#video').videoWidth")>0: break
        time.sleep(0.1)
    cnt_after = int(page.inner_text("#count"))
    check("count survives reload (same day)", cnt_after==cnt_before, f"{cnt_before} -> {cnt_after}")
    check("count stored with today date", page.evaluate("localStorage.getItem('so_count_date')") is not None)

    print("\n=== FLIP + TORCH (graceful) ===")
    try:
        page.click("#flipBtn"); time.sleep(0.6)
        check("flip camera no crash", page.evaluate("document.querySelector('#video').videoWidth")>0)
    except Exception as e:
        check("flip camera no crash", False, str(e)[:80])
    torch_disp = page.evaluate("getComputedStyle(document.querySelector('#torchBtn')).display")
    check("torch correctly hidden when unsupported", torch_disp=="none", f"display={torch_disp}")

    print("\n=== PWA ASSETS ===")
    man = ctx.request.get(f"http://127.0.0.1:{PORT}/manifest.json")
    check("manifest valid json", man.ok and man.json().get("name") is not None)
    check("sw.js reachable", ctx.request.get(f"http://127.0.0.1:{PORT}/sw.js").ok)
    check("icon-192 reachable", ctx.request.get(f"http://127.0.0.1:{PORT}/icons/icon-192.png").ok)
    check("icon-512 reachable", ctx.request.get(f"http://127.0.0.1:{PORT}/icons/icon-512.png").ok)

    print("\n=== SERVICE WORKER REGISTERED ===")
    time.sleep(0.5)
    sw_ok = page.evaluate("navigator.serviceWorker && navigator.serviceWorker.controller!==undefined")
    check("service worker API available", sw_ok)

    print("\n=== V2.1: WATERMARK LOGO ===")
    import base64
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    logo_path = os.path.join(ROOT, "_qa_logo.png")
    with open(logo_path, "wb") as fh: fh.write(png)
    page.set_input_files("#wmFile", logo_path)
    ok=False
    for _ in range(40):
        if page.evaluate("!!wmImg"): ok=True; break
        time.sleep(0.05)
    check("logo loaded into wmImg", ok)
    check("watermark auto-enabled + stored",
          page.evaluate("localStorage.getItem('so_wm_on')")=="1" and page.evaluate("!!localStorage.getItem('so_logo')"))
    page.click("#shutter"); page.wait_for_selector("#sheet.open", timeout=3000)
    page.fill("#nameInput","WM-TEST")
    with page.expect_download(timeout=4000) as wdl:
        page.click("#saveBtn")
    check("capture+save works with watermark ON", wdl.value.suggested_filename=="WM-TEST.jpg", wdl.value.suggested_filename)
    # turn watermark off again so it doesn't bleed into later assertions
    page.evaluate("document.querySelector('#wmOn').checked=false; localStorage.setItem('so_wm_on','0')")
    try: os.remove(logo_path)
    except Exception: pass

    print("\n=== V2.2: GPS STAMP + CSV LOG (offline = coords only) ===")
    ctx.set_offline(True)   # simulasi kebun tanpa sinyal: GPS jalan, alamat/peta tidak
    page.evaluate("setGps(true)")
    gp=False
    for _ in range(60):
        if page.evaluate("!!lastPos"): gp=True; break
        time.sleep(0.05)
    check("geolocation fix received (works offline)", gp)
    check("gps stamp enabled", page.evaluate("gpsOn")==True)
    check("EXIF coords default ON", page.evaluate("exifOn")==True)
    page.click("#shutter"); page.wait_for_selector("#sheet.open", timeout=3000)
    check("Maps link shown on geotagged photo", "show" in (page.get_attribute("#mapsLink","class") or ""))
    check("shotPos captured at shutter", page.evaluate("!!shotPos"))
    page.fill("#nameInput","GPS-TEST")
    with page.expect_download(timeout=4000) as gdl:
        page.click("#saveBtn")
    check("capture+save works with GPS stamp", gdl.value.suggested_filename=="GPS-TEST.jpg", gdl.value.suggested_filename)
    last = page.evaluate("(JSON.parse(localStorage.getItem('so_log')||'[]')).slice(-1)[0]")
    check("photo logged with latitude", bool(last) and abs(float(last.get('lat',0))-(-6.264199))<0.01, str(last)[:80])
    with page.expect_download(timeout=4000) as cdl:
        page.evaluate("exportCSV()")
    check("CSV export downloads .csv", cdl.value.suggested_filename.endswith(".csv"), cdl.value.suggested_filename)
    page.evaluate("setGps(false)")
    ctx.set_offline(False)

    print("\n=== DEMO LIMIT (10/day, only on web demo) ===")
    dp = ctx.new_page()
    dp.add_init_script("window.__DEMO=true; try{localStorage.clear()}catch(e){}")
    dp.goto(f"http://127.0.0.1:{PORT}/index.html")
    dp.click("#startBtn")
    for _ in range(50):
        if dp.evaluate("document.querySelector('#video').videoWidth")>0: break
        time.sleep(0.1)
    check("demo shows /10 on counter", "/10" in dp.inner_text(".pill"), dp.inner_text(".pill"))
    for i in range(10):
        dp.click("#shutter")
        dp.wait_for_selector("#sheet.open", timeout=3000)
        dp.fill("#nameInput", f"demo{i}")
        with dp.expect_download(timeout=3000) as dd:
            dp.click("#saveBtn")
        dd.value
    check("exactly 10 saves allowed", dp.inner_text("#count")=="10", dp.inner_text("#count"))
    dp.click("#shutter")
    time.sleep(0.4)
    check("11th capture blocked (sheet stays closed)", "open" not in dp.get_attribute("#sheet","class"))
    dp.close()

    page.screenshot(path=os.path.join(ROOT,"qa_screenshot.png"))
    browser.close()

httpd.shutdown()

print("\n=== CONSOLE ERRORS ==="); print("\n".join(console_errs) if console_errs else "  (none)")
print("\n=== PAGE (JS) ERRORS ==="); print("\n".join(page_errs) if page_errs else "  (none)")

passed = sum(1 for _,c,_ in results if c); total=len(results)
print(f"\n=== SUMMARY: {passed}/{total} passed ===")
fails=[n for n,c,_ in results if not c]
if fails: print("FAILED:", fails)
sys.exit(0 if passed==total and not page_errs else 1)
