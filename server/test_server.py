#!/usr/bin/env python3
"""Checks the TubeME server end to end in demo mode (no internet). Needs ffmpeg + ffprobe.
Exits non-zero on the first failed check."""
import json, os, shutil, subprocess, sys, tempfile, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18190
BASE = "http://127.0.0.1:%d" % PORT
n = 0

def ok(cond, what):
    global n
    n += 1
    if not cond:
        print("FAILED:", what); raise SystemExit(1)
    print("ok", what)

def get(path, headers=None):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()

def js(path):
    code, _, body = get(path)
    return code, json.loads(body.decode("utf-8"))

def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,codec_type,width,height,profile,sample_rate,channels",
                        "-of", "json", path], stdout=subprocess.PIPE)
    st = json.loads(r.stdout)["streams"]
    return [s for s in st if s["codec_type"] == "video"][0], [s for s in st if s["codec_type"] == "audio"][0]

def boxes(data):
    out, i = [], 0
    while i + 8 <= len(data) and len(out) < 3:
        size = int.from_bytes(data[i:i+4], "big"); out.append(data[i+4:i+8].decode("latin1"))
        if size < 8: break
        i += size
    return out

STUB = r'''
import json, os, subprocess, sys
a = sys.argv[1:]
if "--version" in a: print("stub-1"); sys.exit(0)
url = a[-1]
vid = url.split("v=")[-1]
def items():
    lo, hi = a[a.index("--playlist-items") + 1].split("-")
    return int(lo), int(hi)
if "playlist?list=OL" in url or "/videos" in url:
    assert "--flat-playlist" in a
    lo, hi = items()
    tag = "popvideo" if "list=OL" in url else "chanvid" + ("U" if "/channel/UC" in url else "H")
    print(json.dumps({"entries": [{"id": "%s%03d" % (tag[:8], i), "title": "Hot %d" % i, "uploader": "Up", "duration": 30, "channel_id": "UCxxxxxxxxxxxxxxxxxxxxxx"}
                                  for i in range(lo, min(hi, 20) + 1)]})); sys.exit(0)
if url.startswith("ytsearch"):
    assert "--flat-playlist" in a and "-J" in a
    lo, hi = items()
    assert url.startswith("ytsearch%d:" % hi), url
    print(json.dumps({"entries": [{"id": "realvideo%02d" % i, "title": "Result %d" % i, "channel": "Chan", "channel_id": "UCabc", "duration": 61.0, "view_count": 5}
                                   for i in range(lo - 1, lo + 1)] + [{"id": "short", "title": "not a video id"}, None]})); sys.exit(0)
if "--write-comments" in a:
    ea = a[a.index("--extractor-args") + 1]
    if "max_comments=60" in ea:   # page 2 asks for the first 60
        print(json.dumps({"id": vid, "comments": [{"author": "@p%d" % i, "text": "c%d" % i, "like_count": 1} for i in range(45)]})); sys.exit(0)
    assert "max_comments=30" in ea
    print(json.dumps({"id": vid, "comments": [{"author": "@someone", "text": "x" * 900, "like_count": 4, "_time_text": "2 days ago"},
                                              {"author": "@other", "text": "second", "like_count": None}, {"author": "@empty", "text": ""}, None]
                                             + [{"author": "@f%d" % i, "text": "fill %d" % i} for i in range(30)]})); sys.exit(0)
if "-J" in a:
    if vid == "onlyimages1" and "--ignore-no-formats-error" not in a:
        sys.stderr.write("ERROR: [youtube] onlyimages1: Requested format is not available\\n"); sys.exit(1)
    print(json.dumps({"id": vid, "title": "Real title", "uploader": "Up", "duration": 125, "view_count": 9,
                      "upload_date": "20260102", "description": "d" * 3000})); sys.exit(0)
assert "-f" in a and "bv*" in a[a.index("-f") + 1] and "--merge-output-format" in a and "--no-simulate" in a
if vid == "onlyimages1":
    sys.stderr.write("WARNING: [youtube] No supported JavaScript runtime could be found.\\n"
                     "WARNING: Only images are available for download. use --list-formats to see them\\n"
                     "ERROR: [youtube] onlyimages1: Requested format is not available. Use --list-formats for a list of available formats\\n")
    sys.exit(1)
out = a[a.index("-o") + 1].replace("%(id)s", vid).replace("%(ext)s", "mkv")   # as if picture and sound were joined
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i",
                "sine=frequency=300", "-t", "4", "-c:v", "mpeg4", "-c:a", "aac", out], check=True)
print("some other line"); print(out)
'''

def stub_phase():
    """The real (non-demo) code paths, with a stand-in for yt-dlp so no internet is needed."""
    global BASE
    cache = tempfile.mkdtemp(prefix="tubeme-stub-")
    stub = os.path.join(cache, "stub.py"); open(stub, "w").write(STUB)
    env = dict(os.environ, TUBEME_YTDLP=json.dumps([sys.executable, stub]))
    old, BASE = BASE, "http://127.0.0.1:%d" % (PORT + 1)
    srv = subprocess.Popen([sys.executable, os.path.join(HERE, "tubeme_server.py"), "--no-update", "--port", str(PORT + 1), "--cache", cache],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    try:
        for _ in range(100):
            try:
                get("/tubeme"); break
            except Exception:
                time.sleep(0.2)
        code, res = js("/search?q=x")
        ok(code == 200 and [r["id"] for r in res] == ["realvideo00", "realvideo01"] and res[0]["author"] == "Chan" and res[0]["seconds"] == 61
           and res[0]["channel_id"] == "UCabc", "search through yt-dlp, junk entries dropped")
        code, res = js("/search?q=x&page=3")
        ok(code == 200 and [r["id"] for r in res] == ["realvideo30", "realvideo31"], "search page 3 asks for items 31-45")
        code, ch = js("/channel?id=UCxxxxxxxxxxxxxxxxxxxxxx&page=2")
        ok(code == 200 and ch[0]["id"] == "chanvidU016" and len(ch) == 5, "channel by id, page 2")
        code, ch = js("/channel?id=@some.handle")
        ok(code == 200 and ch[0]["id"] == "chanvidH001", "channel by handle")
        ok(js("/channel?id=../x")[0] == 400, "bad channel id refused")
        code, d = js("/info?id=realvideo00")
        ok(code == 200 and d["title"] == "Real title" and d["author"] == "Up" and d["published"] == "2026-01-02" and len(d["description"]) == 1500,
           "info through yt-dlp")
        ok(js("/info?id=onlyimages1")[0] == 200, "details still load when YouTube offers no formats")
        code, pop = js("/popular")
        ok(code == 200 and pop[0]["id"] == "popvideo001" and len(pop) == 15 and pop[0]["author"] == "Up",
           "popular from YouTube's charts through yt-dlp")
        code, pop2 = js("/popular?page=2")
        ok(code == 200 and pop2[0]["id"] == "popvideo016" and len(pop2) == 5, "popular page 2 from the same chart")
        code, cm = js("/comments?id=realvideo00")
        ok(code == 200 and len(cm) == 30 and cm[0] == {"author": "someone", "text": "x" * 600, "likes": 4, "when": "2 days ago"}
           and cm[1]["likes"] == 0 and cm[1]["when"] == "", "comments through yt-dlp, trimmed and cleaned")
        code, cm2 = js("/comments?id=realvideo00&page=2")
        ok(code == 200 and len(cm2) == 15 and cm2[0]["author"] == "p30", "comments page 2 is the slice after page 1")
        ok(js("/comments?id=realvideo00&page=3")[1] == [], "comments past the end are empty")
        def wait(vid, key):
            t0 = time.time()
            while time.time() - t0 < 60:
                st = js("/prepare?id=%s&q=%s" % (vid, key))[1]
                if st["state"] in ("ready", "error"): return st
                time.sleep(0.2)
            return {"state": "timeout"}
        st = wait("realvideo00", "mp4v-240")
        ok(st["state"] == "ready", "joined (mkv) source converts: %s" % st)
        tmp = os.path.join(cache, "o.mp4"); open(tmp, "wb").write(get(st["url"])[2])
        ok(probe(tmp)[0]["codec_name"] == "mpeg4" and probe(tmp)[0]["width"] == 320, "240p from a joined source")
        st = wait("realvideo00", "h264-360")
        open(tmp, "wb").write(get(st["url"])[2]); v, a = probe(tmp)
        ok(v["codec_name"] == "h264" and a["codec_name"] == "aac" and "Baseline" in v.get("profile", ""),
           "h264-360 is made when YouTube's file isn't H.264 (%s %s)" % (v["codec_name"], v.get("profile")))
        st = wait("onlyimages1", "mp4v-240")
        ok(st["state"] == "error" and "JavaScript runtime (Deno)" in st["error"], "no-formats failure is explained: %s" % st.get("error"))
    finally:
        srv.terminate()
        try: out = srv.communicate(timeout=5)[0].decode("utf-8", "replace")
        except Exception: out = ""
        if "--show-log" in sys.argv: print(out)
        shutil.rmtree(cache, ignore_errors=True)
        BASE = old

def online_phase():
    """A real first start (needs internet): yt-dlp gets installed into tools/, Deno is fetched and runs."""
    global BASE
    work = tempfile.mkdtemp(prefix="tubeme-online-")
    script = os.path.join(work, "tubeme_server.py"); shutil.copy(os.path.join(HERE, "tubeme_server.py"), script)
    env = dict(os.environ); env["PATH"] = os.pathsep.join(p for p in env.get("PATH", "").split(os.pathsep)
                                                           if not any(os.path.exists(os.path.join(p, n)) for n in ("deno", "deno.exe")))
    old, BASE = BASE, "http://127.0.0.1:%d" % (PORT + 2)
    srv = subprocess.Popen([sys.executable, script, "--port", str(PORT + 2)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    try:
        up = False
        for _ in range(900):   # first start downloads ~45 MB
            try:
                get("/tubeme"); up = True; break
            except Exception:
                if srv.poll() is not None: break
                time.sleep(0.5)
        ok(up, "server starts from nothing on %s" % sys.platform)
        exe = os.path.join(work, "tools", "deno.exe" if os.name == "nt" else "deno")
        ok(os.path.isfile(exe), "Deno was fetched into tools/")
        v = subprocess.run([exe, "--version"], stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")
        ok(v.startswith("deno "), "the fetched Deno runs here: %s" % v.splitlines()[0] if v else "no output")
        ok(os.path.isdir(os.path.join(work, "tools", "pylib", "yt_dlp")), "yt-dlp was installed into tools/pylib, not the system")
        e2 = dict(os.environ, PYTHONPATH=os.path.join(work, "tools", "pylib"))
        yv = subprocess.run([sys.executable, "-m", "yt_dlp", "--version"], stdout=subprocess.PIPE, env=e2).stdout.decode().strip()
        ok(len(yv) >= 8 and yv[:2] == "20", "that yt-dlp runs: %s" % yv)
        code, info = js("/tubeme")
        ok(code == 200 and info["demo"] is False and len(info["qualities"]) >= 6, "serving for real with %d qualities" % len(info["qualities"]))
    finally:
        srv.terminate()
        try: out = srv.communicate(timeout=10)[0].decode("utf-8", "replace")
        except Exception: out = ""
        if "--show-log" in sys.argv or not up: print(out)
        shutil.rmtree(work, ignore_errors=True)
        BASE = old

def main():
    if "--online" in sys.argv: online_phase()
    stub_phase()
    cache = tempfile.mkdtemp(prefix="tubeme-test-")
    srv = subprocess.Popen([sys.executable, os.path.join(HERE, "tubeme_server.py"), "--demo", "--port", str(PORT), "--cache", cache],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            try:
                get("/tubeme"); break
            except Exception:
                time.sleep(0.2)
        code, info = js("/tubeme")
        ok(code == 200 and info["tubeme"] == 1 and info["demo"] is True, "identifies itself")
        quals = info["qualities"]
        ok(quals[0] == "h263-144" and "mp4v-240" in quals and "h264-240" in quals and "h264-144" in quals, "offers 144p and 240p: %s" % quals)

        code, res = js("/search?q=cats%20%26%20dogs")
        ok(code == 200 and len(res) == 3 and res[0]["id"] == "demo0000001" and res[0]["seconds"] == 12, "search")
        ok(js("/search?q=")[0] == 400, "empty search refused")
        code, pop = js("/popular")
        ok(code == 200 and len(pop) == 3 and pop[0]["title"] == "Popular demo clip 1", "popular")
        ok(len(js("/popular?page=2")[1]) == 3 and js("/popular?page=4")[1] == [], "popular pages, then empty")
        vid = res[0]["id"]
        code, d = js("/info?id=" + vid)
        ok(code == 200 and d["title"] == "Demo clip 1" and d["seconds"] == 12 and "description" in d, "info")
        ok(js("/info?id=../../etc")[0] == 400, "bad id refused")
        code, cm = js("/comments?id=" + vid)
        ok(code == 200 and len(cm) == 30 and cm[0]["author"] == "Viewer 1" and "likes" in cm[0], "comments")
        ok(len(js("/comments?id=%s&page=3" % vid)[1]) == 10, "comments last page")
        ok(len(js("/channel?id=UCdemodemodemodemodemo00")[1]) == 3, "channel (demo)")
        ok(js("/comments?id=nope")[0] == 400, "comments need a video id")

        for w, want in ((320, 320), (120, 120), (9999, 320)):
            code, h, body = get("/thumb?id=%s&w=%d" % (vid, w))
            ok(code == 200 and h["Content-Type"] == "image/jpeg" and body[:2] == b"\xff\xd8", "thumbnail w=%d is a JPEG" % w)
            tmp = os.path.join(cache, "t.jpg"); open(tmp, "wb").write(body)
            v, = [s for s in json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "json", tmp],
                                                      stdout=subprocess.PIPE).stdout)["streams"]]
            ok(v["width"] == want, "thumbnail w=%d is %d wide" % (w, want))

        ok(js("/prepare?id=%s&q=h999" % vid)[0] == 404, "unknown quality refused")
        want = {  # key: (video codec, width, height, container)
            "h263-144": ("h263", 176, 144, "3gp"), "h264-144": ("h264", 256, 144, "mp4"),
            "mp4v-240": ("mpeg4", 320, 176, "mp4"), "h264-240": ("h264", 320, 176, "mp4"),
            "h263-288": ("h263", 352, 288, "3gp"), "mp4v-270": ("mpeg4", 480, 256, "mp4"),
            "mp4v-360": ("mpeg4", 640, 352, "mp4"), "h264-360": ("h264", 640, 360, "mp4"),
        }
        saw_progress = False
        for key in quals:
            url = None
            t0 = time.time()
            while time.time() - t0 < 60:
                code, st = js("/prepare?id=%s&q=%s" % (vid, key))
                ok(code == 200, "prepare %s answers" % key) if url is None and time.time() - t0 < 0.5 else None
                if st["state"] == "ready": url = st["url"]; break
                if st["state"] == "error": ok(False, "conversion %s: %s" % (key, st["error"]))
                if st["state"] in ("queued", "fetching", "converting"): saw_progress = True
                time.sleep(0.2)
            ok(url is not None and url.endswith(want[key][3]), "%s ready: %s" % (key, url))
            code, h, body = get(url)
            ok(code == 200 and int(h["Content-Length"]) == len(body) == st["bytes"] and h.get("Accept-Ranges") == "bytes", "%s served whole, %d KB" % (key, len(body) // 1024))
            ok(h["Content-Type"] == ("video/3gpp" if want[key][3] == "3gp" else "video/mp4"), "%s content type" % key)
            ok(boxes(body)[:2] == ["ftyp", "moov"], "%s can play while downloading (%s)" % (key, boxes(body)))
            tmp = os.path.join(cache, "o." + want[key][3]); open(tmp, "wb").write(body)
            v, a = probe(tmp)
            ok((v["codec_name"], v["width"], v["height"]) == want[key][:3], "%s is %s %dx%d" % (key, v["codec_name"], v["width"], v["height"]))
            ok(v["width"] % 16 == 0 and (v["height"] % 16 == 0 or key in ("h264-360", "h264-144")), "%s size suits picky decoders" % key)
            if v["codec_name"] == "h264" and key != "h264-360":
                ok("Baseline" in v.get("profile", ""), "%s is Baseline profile (%s)" % (key, v.get("profile")))
            ok(a["codec_name"] in ("aac", "amr_nb"), "%s has sound (%s)" % (key, a["codec_name"]))
        ok(saw_progress, "progress states were reported while converting")

        # resuming / seeking
        code, h, part = get(url, {"Range": "bytes=100-199"})
        ok(code == 206 and len(part) == 100 and part == body[100:200] and h["Content-Range"] == "bytes 100-199/%d" % len(body), "range request")
        code, h, tail = get(url, {"Range": "bytes=-50"})
        ok(code == 206 and tail == body[-50:], "suffix range")
        code, h, rest = get(url, {"Range": "bytes=%d-" % (len(body) - 10)})
        ok(code == 206 and rest == body[-10:], "open-ended range (resume)")
        ok(get(url, {"Range": "bytes=%d-" % (len(body) + 5)})[0] == 416, "range past the end refused")
        ok(get("/file/..%2F..%2Fsecret.mp4")[0] == 404 and get("/file/nope.txt")[0] == 404, "only converted files are served")
        # second request for a finished conversion is instant
        t0 = time.time(); code, st = js("/prepare?id=%s&q=%s" % (vid, quals[0]))
        ok(st["state"] == "ready" and time.time() - t0 < 1, "finished conversions are kept")
        print("SERVER TESTS PASSED (%d checks)" % n)
    finally:
        srv.terminate()
        try: out = srv.communicate(timeout=5)[0].decode("utf-8", "replace")
        except Exception: out = ""
        if "--show-log" in sys.argv: print(out)
        shutil.rmtree(cache, ignore_errors=True)

main()
