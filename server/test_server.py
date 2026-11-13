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

def main():
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
        vid = res[0]["id"]
        code, d = js("/info?id=" + vid)
        ok(code == 200 and d["title"] == "Demo clip 1" and d["seconds"] == 12 and "description" in d, "info")
        ok(js("/info?id=../../etc")[0] == 400, "bad id refused")

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
