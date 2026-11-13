#!/usr/bin/env python3
"""Mock YtAPILegacy / yt2009 / Invidious servers + the shared instance list.
Made-up videos only. Prints a CHECK line per endpoint hit; exits 1 if any expected one is missing."""
import json, os, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer as HTTPServer
from urllib.parse import urlparse, parse_qs

HITS = set()
VIDEO = bytes(range(256)) * 300  # 76800 bytes of "video"
LEG, DEAD, Y09, Y09BAD, INV, Y09OLD, Y09HANG, OWN = 18081, 18082, 18083, 18084, 18085, 18086, 18087, 18088
PREP = {}

def png(w, h):
    import zlib, struct
    rows = b"".join(b"\0" + bytes(sum(([40 + x * 200 // w, 60 + y * 150 // h, 160] for x in range(w)), [])) for y in range(h))
    ch = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + ch(b"IDAT", zlib.compress(rows)) + ch(b"IEND", b"")
THUMB = png(120, 90)

def vid(i): return "abcDEF_-%03d" % i

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def log_message(self, *a): pass
    def send(self, code, body=b"", ctype="application/json", headers=None, length=True):
        if isinstance(body, (dict, list)): body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        if length: self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)
    def hit(self, name):
        HITS.add(name); print("CHECK", name, flush=True)
    def do_GET(self):
        port = self.server.server_port
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path
        if "TubeME/" not in self.headers.get("User-Agent", ""): return self.send(400, {"error": "no UA"})
        if port == DEAD: return self.send(500, b"boom", "text/plain")
        if port == LEG:
            if p == "/notPipe.json":
                self.hit("list")
                return self.send(200, {"invidious": ["http://127.0.0.1:%d/" % INV], "piped": ["https://piped.invalid"],
                    "yt2009": ["http://127.0.0.1:%d" % Y09BAD, "http://127.0.0.1:%d" % Y09],
                    "ytapilegacy": ["http://127.0.0.1:%d" % DEAD, "http://127.0.0.1:%d" % LEG]})
            if p == "/legacyonly.json":   # a list with one YtAPILegacy server and nothing else
                return self.send(200, {"invidious": [], "yt2009": [], "ytapilegacy": ["http://127.0.0.1:%d" % LEG]})
            if p == "/health":
                self.hit("health"); return self.send(200, {"status": "ok"})
            if p == "/get_search_videos.php":
                self.hit("legacy-search:" + q["query"][0])
                return self.send(200, [{"video_id": vid(i), "title": "Tést \"vidéo\" №%d \U0001F600" % i, "author": "Canal Ç",
                    "duration": "3:2%d" % i, "views": "1,234 views", "thumbnail": "/thumbnail/" + vid(i),
                    "channel_thumbnail": "/channel_icon/x"} for i in range(3)])
            if p == "/get_top_videos.php":
                self.hit("legacy-top")
                return self.send(200, [{"video_id": vid(9), "title": "Top one", "author": "Someone", "duration": "PT1H2M3S",
                    "thumbnail": "/thumbnail/x", "channel_thumbnail": ""}])
            if p == "/get-ytvideo-info.php":
                i = q["video_id"][0]
                if i == "gone_gone_1": return self.send(200, {"title": "", "author": "", "comments": []})
                self.hit("legacy-info")
                return self.send(200, {"title": "Info title", "author": "Info author", "description": "line1\nline2 \u00e9",
                    "views": "4242", "likes": "7", "duration": "PT16M21S", "published_at": "Jan 2, 2026",
                    "thumbnail": "/thumbnail/" + i, "channel_thumbnail": "", "subscriberCount": "5", "comments": [], "n": 1.5, "x": None, "t": True})
            if p == "/direct_url":
                kind = q.get("codec", ["h264-" + q.get("quality", ["?"])[0]])[0]
                self.hit("legacy-video:" + kind)
                if kind == "h263":   # conversion: no length, like a live ffmpeg pipe
                    return self.send(200, VIDEO, "video/3gpp", length=False)
                if os.environ.get("MOCK_TRUNCATE"):   # claims more than it sends, like a dropped connection
                    self.send_response(200); self.send_header("Content-Type", "video/mp4")
                    self.send_header("Content-Length", str(len(VIDEO) + 50000)); self.end_headers()
                    self.wfile.write(VIDEO); return
                if os.environ.get("MOCK_ERRORPAGE"):  # an error page with a 200, as some proxies do
                    return self.send(200, b"<html>busy</html>", "text/html")
                return self.send(200, VIDEO, "video/mp4")
            if p.startswith("/thumbnail/"):
                self.hit("thumb:" + q.get("quality", ["-"])[0]); return self.send(200, THUMB, "image/png")
        if port == Y09BAD: return self.send(404, b"nope", "text/plain")
        if port == Y09:
            if p in ("/http_3gp_288", "/http_mpeg4", "/http_mpeg4_360", "/channel_fh264_getvideo", "/mp4_144", "/http_3gp"):
                self.hit("yt2009" + p)
                return self.send(302, b"", headers={"Location": "/assets/%s%s.bin" % (q["v"][0], p.replace("/", "-"))})
            if p == "/slow":
                time.sleep(4); return self.send(302, b"", headers={"Location": "/assets/late.bin"})
            if p.startswith("/assets/"):
                self.hit("yt2009-file")
                if os.environ.get("MOCK_ERRORPAGE"): return self.send(200, b"<html>busy</html>", "text/html")
                return self.send(200, VIDEO, "video/mp4")
        if port == Y09OLD:   # stock yt2009: no _360/_288 addresses, and /mp4_144 behind a sign-in
            if p == "/http_mpeg4":
                return self.send(302, b"", headers={"Location": "/assets/%s-mpeg4.mp4" % q["v"][0]})
            if p == "/mp4_144":
                return self.send(302, b"", headers={"Location": "/auth.html?redir=rtsp"})
            return self.send(404, b"Cannot GET", "text/html")
        if port == OWN:      # speaks the TubeME server protocol (server/tubeme_server.py)
            if p == "/tubeme":
                self.hit("own-tubeme")
                return self.send(200, {"tubeme": 1, "version": "0.1.0", "demo": True, "sound3gp": "aac",
                    "qualities": ["h263-144", "h264-144", "mp4v-240", "h264-240", "mp4v-360"]})
            if p == "/search":
                self.hit("own-search")
                return self.send(200, [{"id": vid(i), "title": "Own r\u00e9sultat %d" % i, "author": "Mine", "seconds": 65 + i, "views": 0 if i == 0 else 42}
                                       for i in range(2)])
            if p == "/info":
                self.hit("own-info")
                return self.send(200, {"id": q["id"][0], "title": "Own title", "author": "Mine", "seconds": 125, "views": 7,
                    "published": "2026-01-02", "description": "own description"})
            if p == "/thumb":
                self.hit("own-thumb:" + q.get("w", ["-"])[0]); return self.send(200, THUMB, "image/png")
            if p == "/prepare":
                key = q["q"][0]
                if key not in ("h263-144", "h264-144", "mp4v-240", "h264-240", "mp4v-360"):
                    return self.send(404, {"error": "this server doesn't make quality '%s'" % key})
                if q["id"][0] == "brokenbroke":
                    return self.send(200, {"state": "error", "error": "Video unavailable"})
                k = q["id"][0] + key; PREP[k] = PREP.get(k, 0) + 1
                self.hit("own-prepare")
                if PREP[k] == 1: return self.send(200, {"state": "fetching", "percent": 0})
                if PREP[k] == 2: return self.send(200, {"state": "converting", "percent": 40})
                if PREP[k] == 3: return self.send(200, {"state": "converting", "percent": 85})
                return self.send(200, {"state": "ready", "url": "/file/%s-%s.mp4" % (q["id"][0], key), "bytes": len(VIDEO)})
            if p.startswith("/file/"):
                self.hit("own-file"); return self.send(200, VIDEO, "video/mp4")
            return self.send(404, {"error": "not found"})
        if port == Y09HANG:  # a server whose conversion is stuck: answers far too late
            if p == "/health": return self.send(404, b"", "text/html")
            time.sleep(8); return self.send(302, b"", headers={"Location": "/assets/late.mp4"})
        if port == INV:
            if p == "/api/v1/search":
                self.hit("inv-search")
                return self.send(200, [{"type": "video", "videoId": vid(5), "title": "Inv result", "author": "Inv author",
                    "lengthSeconds": 3725, "viewCount": 99}])
            if p.startswith("/api/v1/videos/"):
                self.hit("inv-info")
                return self.send(200, {"videoId": p.split("/")[-1], "title": "Inv title", "author": "Inv author", "lengthSeconds": 61,
                    "viewCount": 1000, "description": "d", "publishedText": "2 days ago",
                    "formatStreams": [{"url": "/latest_version?id=x&itag=18", "itag": "18"}]})
        self.send(404, {"error": "not found"})

def main():
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    for port in (LEG, DEAD, Y09, Y09BAD, INV, Y09OLD, Y09HANG, OWN):
        s = HTTPServer(("127.0.0.1", port), H)
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("mock up", flush=True)
    time.sleep(secs)
    want = ["own-tubeme", "own-search", "own-info", "own-prepare", "own-file", "own-thumb:320", "health", "list", "legacy-search:gatos & cães", "legacy-top", "legacy-info", "legacy-video:h263", "legacy-video:mpeg4",
            "legacy-video:h264-360", "yt2009/http_3gp_288", "yt2009/http_mpeg4", "yt2009/http_mpeg4_360",
            "yt2009/channel_fh264_getvideo", "yt2009/mp4_144", "yt2009/http_3gp", "yt2009-file", "inv-search", "inv-info", "thumb:default"]
    missing = [w for w in want if w not in HITS]
    print("MOCK", "OK" if not missing else "MISSING %s" % missing, flush=True)
    sys.exit(1 if missing else 0)

main()
