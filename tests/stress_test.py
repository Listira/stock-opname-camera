import sys, time, http.server, socketserver, threading, os, functools
sys.stdout.reconfigure(encoding="utf-8")

PORT = 8078
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # parent of tests/
Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
httpd = socketserver.TCPServer(("127.0.0.1", PORT), Handler)
httpd.RequestHandlerClass.log_message = lambda *a, **k: None
threading.Thread(target=httpd.serve_forever, daemon=True).start()

from playwright.sync_api import sync_playwright

results=[]
def check(name, cond, detail=""):
    results.append((name,bool(cond),detail))
    print(("  PASS " if cond else "  FAIL ")+name+((" -> "+str(detail)) if detail else ""))

console_errs=[]; page_errs=[]

def start(page):
    page.click("#startBtn")
    for _ in range(60):
        if page.evaluate("document.querySelector('#video').videoWidth")>0: return True
        time.sleep(0.1)
    return False

def heap(page):
    return page.evaluate("performance.memory ? performance.memory.usedJSHeapSize : 0")

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True, args=[
        "--use-fake-device-for-media-stream","--use-fake-ui-for-media-stream",
        "--autoplay-policy=no-user-gesture-required","--enable-precise-memory-info",
        "--js-flags=--expose-gc",
    ])
    ctx=browser.new_context(accept_downloads=True, viewport={"width":412,"height":860}, permissions=["camera"])
    page=ctx.new_page()
    page.on("console", lambda m: console_errs.append(m.text) if m.type=="error" else None)
    page.on("pageerror", lambda e: page_errs.append(str(e)))
    page.goto(f"http://127.0.0.1:{PORT}/index.html")
    assert start(page), "camera failed to start"

    # ============================================================
    print("\n=== STRESS 1: RAPID CAPTURE + SAVE x250 ===")
    N=250
    h0=heap(page)
    t0=time.time()
    fail=0; sizes=[]
    for i in range(N):
        page.click("#shutter")
        page.wait_for_selector("#sheet.open", timeout=3000)
        page.fill("#nameInput", f"aset-{i:04d}")
        try:
            with page.expect_download(timeout=3000) as di:
                page.click("#saveBtn")
            if i % 50 == 0:   # sample file sizes (don't stat all 250)
                sizes.append(os.path.getsize(di.value.path()))
        except Exception:
            fail+=1
    dt=time.time()-t0
    h1=heap(page)
    cnt=int(page.inner_text("#count"))
    check(f"all {N} saves succeeded (no timeout)", fail==0, f"{fail} failed")
    check("counter matches saves", cnt==N, f"count={cnt}")
    check("sampled files are real jpeg (>1KB)", all(s>1024 for s in sizes), f"sizes={sizes}")
    check("throughput ok (<250ms/shot avg, automation-bound)", dt/N < 0.25, f"{dt/N*1000:.0f} ms/shot, total {dt:.1f}s")
    s1_per = dt/N   # baseline per-iteration automation overhead
    print(f"   heap: {h0/1e6:.1f}MB -> {h1/1e6:.1f}MB (Δ {(h1-h0)/1e6:+.1f}MB)")

    print("\n=== STRESS 2: BLOB URL LEAK CHECK ===")
    # only the current blob should be alive; old ones revoked each shutter
    leaked = page.evaluate("""async ()=>{
        // count live blob: urls is not directly queryable; instead force GC and read heap delta
        return true;
    }""")
    # measure heap before/after gc to confirm it can reclaim
    if page.evaluate("typeof window.gc === 'function'"):
        page.evaluate("window.gc()")
        time.sleep(0.3)
        h_gc=heap(page)
        check("heap reclaimable after GC (<+15MB over baseline)", (h_gc-h0)/1e6 < 15, f"{(h_gc-h0)/1e6:+.1f}MB after gc")
    else:
        check("heap growth bounded (<+20MB over 250 shots)", (h1-h0)/1e6 < 20, f"{(h1-h0)/1e6:+.1f}MB")

    # ============================================================
    print("\n=== STRESS 3: DUPLICATE-NAME STORM x200 (dedup delegated to OS) ===")
    t0=time.time(); names=[]
    for i in range(200):
        page.click("#shutter"); page.wait_for_selector("#sheet.open",timeout=3000)
        page.fill("#nameInput","SAMA")
        with page.expect_download(timeout=3000) as di:
            page.click("#saveBtn")
        names.append(di.value.suggested_filename)
    dt=time.time()-t0
    check("all 200 dup-name saves dispatched", len(names)==200, f"{len(names)}")
    check("names sent AS-IS (MediaStore/OS does ' (2)')", all(n=="SAMA.jpg" for n in names), f"{names[0]} ... {names[-1]}")
    check("dup names add no overhead (per-shot <= 1.5x baseline)", dt/200 <= s1_per*1.5, f"{dt/200*1000:.0f} vs {s1_per*1000:.0f} ms baseline")

    # ============================================================
    print("\n=== STRESS 4: PATHOLOGICAL NAMES ===")
    cases=[
        ("x"*2000, "very long (2000 chars) -> capped <=124"),
        ("////::::****", "all-illegal -> empty -> must be blocked"),
        ("   ///   ", "spaces+illegal -> empty -> blocked"),
        ("日本語アセット-名前", "unicode/CJK"),
        ("aset😀📸🔧", "emoji"),
        ("....hidden", "leading dots"),
        ("CON", "windows reserved-ish word"),
    ]
    for val,desc in cases:
        page.click("#shutter"); page.wait_for_selector("#sheet.open",timeout=3000)
        page.fill("#nameInput", val)
        blocked=False; fname=None
        try:
            with page.expect_download(timeout=1500) as di:
                page.click("#saveBtn")
            fname=di.value.suggested_filename
        except Exception:
            blocked=True
        if "blocked" in desc:
            sheet_open = "open" in page.get_attribute("#sheet","class")
            check(f"[{desc}]", blocked and sheet_open, "correctly refused")
            page.click("#retakeBtn")
        else:
            ok = (fname is not None) and fname.endswith(".jpg") and not any(c in fname for c in '\\/:*?"<>|') and len(fname)<=124
            # filename must be safe AND length-bounded for real filesystems
            check(f"[{desc}] -> safe filename (len={len(fname) if fname else 0})", ok, (fname[:40]+"...") if fname and len(fname)>40 else fname)

    # ============================================================
    print("\n=== STRESS 5: RACE — shutter then INSTANT save (same JS tick) ===")
    # reload fresh so lastBlobUrl starts null -> worst case
    page.goto(f"http://127.0.0.1:{PORT}/index.html")
    assert start(page)
    # save before blob ready -> QUEUED, auto-fires exactly once when blob completes
    with page.expect_download(timeout=3000) as di:
        page.evaluate("""()=>{
            document.querySelector('#shutter').click();
            document.querySelector('#nameInput').value='RACE';
            document.querySelector('#saveBtn').click();   // blob not ready yet -> queued
        }""")
    sz=os.path.getsize(di.value.path())
    check("queued save auto-fires once blob ready (real jpeg)", sz>1024, f"{sz} bytes")
    check("saved with correct name", di.value.suggested_filename=="RACE.jpg", di.value.suggested_filename)
    time.sleep(0.3)
    check("sheet closed after queued save", "open" not in page.get_attribute("#sheet","class"))
    # retake must CANCEL a queued save (no ghost download)
    ghost=[]
    page.on("download", lambda d: ghost.append(d))
    page.evaluate("""()=>{
        document.querySelector('#shutter').click();
        document.querySelector('#nameInput').value='GHOST';
        document.querySelector('#saveBtn').click();   // queued
        document.querySelector('#retakeBtn').click(); // cancel!
    }""")
    time.sleep(0.6)
    check("retake cancels queued save (no ghost file)", len(ghost)==0, f"{len(ghost)} ghost downloads")

    # ============================================================
    print("\n=== STRESS 6: CAMERA RESTART STORM (flip x30) ===")
    t0=time.time()
    for _ in range(30):
        page.click("#flipBtn"); time.sleep(0.05)
    # let last restart settle
    ok=False
    for _ in range(40):
        if page.evaluate("document.querySelector('#video').videoWidth")>0: ok=True; break
        time.sleep(0.1)
    live_tracks = page.evaluate("""()=>{
        const s=document.querySelector('#video').srcObject;
        if(!s) return -1;
        return s.getVideoTracks().filter(t=>t.readyState==='live').length;
    }""")
    check("camera survives 30 rapid flips", ok)
    check("exactly 1 live track (old streams cleaned up)", live_tracks==1, f"live tracks={live_tracks}")
    print(f"   30 flips in {time.time()-t0:.1f}s")

    browser.close()

httpd.shutdown()
print("\n=== CONSOLE ERRORS ==="); print("\n".join(console_errs) if console_errs else "  (none)")
print("\n=== PAGE (JS) ERRORS ==="); print("\n".join(page_errs) if page_errs else "  (none)")
passed=sum(1 for _,c,_ in results if c); total=len(results)
print(f"\n=== SUMMARY: {passed}/{total} passed ===")
fails=[n for n,c,_ in results if not c]
if fails: print("FAILED:", fails)
sys.exit(0 if passed==total and not page_errs else 1)
