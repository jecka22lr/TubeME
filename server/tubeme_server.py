#!/usr/bin/env python3
"""TubeME server: fetches YouTube videos and converts them for old phones.

Run it on a PC, then put its address in TubeME (Settings > My TubeME server).

Runs on Windows, macOS and Linux. Needs Python 3.8+ and ffmpeg on the PATH. Everything
else it looks after itself, inside a "tools" folder next to this script (nothing is
installed system-wide): at start, and every 12 hours while running, it installs or
updates yt-dlp, and it fetches Deno (the JavaScript runtime yt-dlp needs for YouTube)
if none is installed.

    python tubeme_server.py                # serve on port 8765
    python tubeme_server.py --no-update    # don't install or update anything
    python tubeme_server.py --port 9000 --cache D:\\tubeme-cache --max-cache-mb 4000
    python tubeme_server.py --demo         # no internet needed: test videos only

What the phone app asks for:
    /tubeme                 what this server is and which qualities it makes
    /search?q=...           search results
    /info?id=...            one video's details
    /thumb?id=...&w=320     thumbnail, scaled to the phone's screen
    /prepare?id=...&q=...   start (or check on) a conversion; answers with progress
    /file/<name>            the converted file (supports resuming and seeking)
"""
import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "0.3.0"
ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
FILE_RE = re.compile(r"^[A-Za-z0-9_-]{11}-[a-z0-9-]+\.(mp4|3gp)$")

# Every quality the app knows by name. "box" is the largest picture; "fixed" means the codec only
# allows that exact size (H.263), so the picture is padded with black bars instead of resized freely.
PROFILES = {
    "h263-144": dict(ext="3gp", box=(176, 144), fixed=True, audio="narrow", fps=15,
                     video=["-c:v", "h263", "-b:v", "96k", "-g", "45"]),
    "h264-144": dict(ext="mp4", box=(256, 144), audio="low", fps=25,
                     video=["-c:v", "libx264", "-profile:v", "baseline", "-level", "1.2", "-preset", "veryfast",
                            "-b:v", "140k", "-maxrate", "180k", "-bufsize", "360k", "-g", "75"]),
    "mp4v-240": dict(ext="mp4", box=(320, 240), audio="mid", fps=25,
                     video=["-c:v", "mpeg4", "-vtag", "mp4v", "-b:v", "320k", "-g", "75", "-bf", "0"]),
    "h264-240": dict(ext="mp4", box=(320, 240), audio="mid", fps=25,
                     video=["-c:v", "libx264", "-profile:v", "baseline", "-level", "1.3", "-preset", "veryfast",
                            "-b:v", "260k", "-maxrate", "340k", "-bufsize", "680k", "-g", "75"]),
    "h263-288": dict(ext="3gp", box=(352, 288), fixed=True, audio="narrow", fps=20,
                     video=["-c:v", "h263", "-b:v", "320k", "-g", "60"]),
    "mp4v-270": dict(ext="mp4", box=(480, 272), audio="mid", fps=25,
                     video=["-c:v", "mpeg4", "-vtag", "mp4v", "-b:v", "480k", "-g", "75", "-bf", "0"]),
    "mp4v-360": dict(ext="mp4", box=(640, 360), audio="high", fps=25,
                     video=["-c:v", "mpeg4", "-vtag", "mp4v", "-b:v", "750k", "-g", "75", "-bf", "0"]),
    "h264-360": dict(ext="mp4", copy=True),
}
ORDER = ["h263-144", "h264-144", "mp4v-240", "h264-240", "h263-288", "mp4v-270", "mp4v-360", "h264-360"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def run(cmd, **kw):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)


def short(e):
    """An error trimmed to what fits on a phone screen."""
    msg = str(e).replace("\n", " ")
    if "ERROR:" in msg:
        msg = msg.split("ERROR:", 1)[1]
    for cut in ("; please report", " (caused by"):
        msg = msg.split(cut, 1)[0]
    return msg.strip()[:160]


def fit(sw, sh, bw, bh):
    """Largest size inside the box with the source's shape, both sides multiples of 16
    (several phone decoders refuse anything else)."""
    scale = min(bw / sw, bh / sh)
    w = max(16, int(sw * scale) // 16 * 16)
    h = max(16, int(sh * scale) // 16 * 16)
    return w, h


# What to fetch from YouTube, best choice first: the ready-made 360p file, then any single file up
# to 360p, then separate picture and sound (which yt-dlp joins with ffmpeg), preferring H.264.
SOURCE_FORMAT = ("18/b[height<=360][ext=mp4]/bv*[height<=360][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=360]+ba/"
                 "b[height<=480]/bv*[height<=480]+ba/b/bv*+ba")
UPDATE_EVERY = 12 * 3600
DENO_MAX_AGE = 30 * 86400


class Tools:
    """yt-dlp and the JavaScript runtime it needs, kept installed and current.

    yt-dlp is run as a separate program (python -m yt_dlp), so an update takes effect on the very
    next request without restarting the server."""

    def __init__(self, tools_dir, auto_update):
        self.dir = tools_dir
        # yt-dlp lives in its own folder here, so nothing is installed into the system's Python
        # (which macOS and most Linux systems refuse anyway)
        self.pylib = os.path.join(tools_dir, "pylib")
        self.auto = auto_update
        self.lock = threading.Lock()
        self.last_update = 0
        self.js = None
        # TUBEME_YTDLP (a JSON list) swaps in another command; used by the tests
        self.cmd = json.loads(os.environ["TUBEME_YTDLP"]) if os.environ.get("TUBEME_YTDLP") else None

    def env(self):
        e = dict(os.environ)
        if os.path.isdir(self.pylib):
            e["PYTHONPATH"] = self.pylib + (os.pathsep + e["PYTHONPATH"] if e.get("PYTHONPATH") else "")
        e["PYTHONIOENCODING"] = "utf-8"
        return e

    def ytdlp_version(self):
        r = run(self.ytdlp_cmd() + ["--version"], env=self.env())
        return r.stdout.decode("utf-8", "replace").strip() if r.returncode == 0 else None

    def ytdlp_cmd(self):
        return self.cmd or [sys.executable, "-m", "yt_dlp"]

    def update(self, why, force=False):
        """Installs or updates yt-dlp and makes sure a JavaScript runtime is there.
        Returns True when it actually looked (it holds off for 10 minutes between looks)."""
        with self.lock:
            if not force and time.time() - self.last_update < 600:
                return False
            self.last_update = time.time()
            if self.auto and not self.cmd:
                before = self.ytdlp_version()
                log("checking for yt-dlp updates (%s)" % why)
                os.makedirs(self.pylib, exist_ok=True)
                r = run([sys.executable, "-m", "pip", "install", "-U", "--quiet", "--disable-pip-version-check",
                         "--no-warn-script-location", "--target", self.pylib, "yt-dlp[default]"])
                after = self.ytdlp_version()
                if r.returncode:
                    tail = r.stderr.decode("utf-8", "replace").strip().splitlines()[-1:]
                    log("couldn't update yt-dlp:", tail[0][:300] if tail else "pip failed")
                    if b"No module named pip" in r.stderr:
                        log("this Python has no pip. Debian/Ubuntu: sudo apt install python3-pip")
                if after is None:
                    log("yt-dlp is not installed and couldn't be installed (is pip available, is there internet?)")
                elif after != before:
                    log("yt-dlp %s -> %s" % (before or "not installed", after))
                else:
                    log("yt-dlp %s is current" % after)
            self.js = self.find_js()
            return True

    def find_js(self):
        """A JavaScript runtime for yt-dlp as (name, path-or-None). YouTube hides every video format
        from yt-dlp without one."""
        if shutil.which("deno"):
            return ("deno", None)
        exe = os.path.join(self.dir, "deno.exe" if os.name == "nt" else "deno")
        fresh = os.path.exists(exe) and time.time() - os.path.getmtime(exe) < DENO_MAX_AGE
        if not fresh and self.auto:
            try:
                self.fetch_deno(exe)
            except Exception as e:
                log("couldn't fetch Deno:", short(e))
        if os.path.exists(exe):
            return ("deno", exe)
        for other in ("node", "bun"):
            if shutil.which(other):
                return (other, None)
        log("NO JAVASCRIPT RUNTIME: YouTube will give yt-dlp no video formats. Install Deno "
            "(Windows: winget install DenoLand.Deno) and restart.")
        return None

    def fetch_deno(self, exe):
        machine = platform.machine().lower()
        arm = machine in ("arm64", "aarch64")
        if sys.platform.startswith("win"):
            target = "aarch64-pc-windows-msvc" if arm else "x86_64-pc-windows-msvc"
        elif sys.platform == "darwin":
            target = "aarch64-apple-darwin" if arm else "x86_64-apple-darwin"
        else:
            target = "aarch64-unknown-linux-gnu" if arm else "x86_64-unknown-linux-gnu"
        url = "https://github.com/denoland/deno/releases/latest/download/deno-%s.zip" % target
        log("fetching Deno (JavaScript runtime for yt-dlp), about 45 MB, from", url)
        os.makedirs(self.dir, exist_ok=True)
        tmp = exe + ".zip"
        for attempt in (1, 2, 3):
            try:
                # 30 s without a single byte counts as stalled, instead of waiting forever
                with urllib.request.urlopen(url, timeout=30) as u, open(tmp, "wb") as f:
                    total = int(u.headers.get("Content-Length") or 0)
                    done, shown = 0, time.time()
                    while True:
                        chunk = u.read(262144)
                        if not chunk:
                            break
                        f.write(chunk)
                        done += len(chunk)
                        if time.time() - shown >= 5:
                            shown = time.time()
                            log("  Deno: %d MB%s" % (done >> 20, " of %d MB" % (total >> 20) if total else ""))
                if total and done < total:
                    raise IOError("download stopped at %d of %d MB" % (done >> 20, total >> 20))
                break
            except Exception as e:
                if attempt == 3:
                    raise
                log("  Deno download failed (%s), trying again" % short(e))
        with zipfile.ZipFile(tmp) as z:
            name = [n for n in z.namelist() if os.path.basename(n).lower() in ("deno", "deno.exe")][0]
            with z.open(name) as src, open(exe + ".new", "wb") as dst:
                shutil.copyfileobj(src, dst)
        os.remove(tmp)
        os.replace(exe + ".new", exe)
        os.chmod(exe, 0o755)
        log("Deno saved to", exe)

    def ytdlp(self, args, timeout=600):
        """Runs yt-dlp. Returns its standard output; raises with a readable reason on failure.
        One automatic retry after an update check, since most failures are an outdated yt-dlp."""
        for attempt in (0, 1):
            cmd = self.ytdlp_cmd() + ["--ignore-config", "--no-playlist", "--no-progress"]
            if self.js:
                cmd += ["--js-runtimes", self.js[0] + (":" + self.js[1] if self.js[1] else "")]
                # lets yt-dlp fetch its YouTube challenge solver when the installed one is out of date
                cmd += ["--remote-components", "ejs:github"]
            try:
                r = run(cmd + args, timeout=timeout, env=self.env())
            except subprocess.TimeoutExpired:
                raise RuntimeError("YouTube took too long to answer")
            err = r.stderr.decode("utf-8", "replace")
            if r.returncode == 0:
                return r.stdout.decode("utf-8", "replace")
            for line in err.strip().splitlines()[-12:]:
                log("  yt-dlp:", line[:300])
            if attempt == 0 and self.update("a request failed"):
                continue
            if "JavaScript runtime" in err or "Only images are available" in err or "challenge solving failed" in err:
                raise RuntimeError("YouTube gave no video formats: the server's JavaScript runtime (Deno) "
                                   "is missing or not working. See the server window.")
            errors = [l for l in err.splitlines() if l.startswith("ERROR:")]
            raise RuntimeError(errors[-1] if errors else (err.strip().splitlines() or ["yt-dlp failed"])[-1])


class Server:
    def __init__(self, cache, demo, max_cache_mb, workers, tools=None):
        self.tools = tools
        self.cache = os.path.abspath(cache)
        self.src_dir = os.path.join(self.cache, "src")
        self.out_dir = os.path.join(self.cache, "out")
        self.thumb_dir = os.path.join(self.cache, "thumb")
        for d in (self.src_dir, self.out_dir, self.thumb_dir):
            os.makedirs(d, exist_ok=True)
        self.demo = demo
        self.max_cache = max_cache_mb * 1024 * 1024
        self.jobs = {}
        self.lock = threading.Lock()
        self.src_locks = {}
        self.slots = threading.Semaphore(workers)
        self.info_cache = {}
        encoders = run(["ffmpeg", "-hide_banner", "-encoders"]).stdout.decode("utf-8", "replace")
        self.has_amr = "libopencore_amrnb" in encoders
        self.qualities = []
        for key in ORDER:
            p = PROFILES[key]
            need = None if p.get("copy") else p["video"][1]
            if need is None or re.search(r"\s%s\s" % re.escape(need), encoders):
                self.qualities.append(key)
            else:
                log("this ffmpeg has no %s encoder: %s is switched off" % (need, key))
        if not self.has_amr:
            log("this ffmpeg has no AMR encoder: 3GP files get AAC sound instead "
                "(fine on Symbian; very old phones may play them silent)")

    # ---------- YouTube side ----------

    def search(self, q):
        if self.demo:
            return [dict(id="demo%07d" % i, title="Demo clip %d (%s)" % (i, q), author="TubeME demo",
                         seconds=8 + i * 4, views=1000 * i) for i in range(1, 4)]
        res = json.loads(self.tools.ytdlp(["--flat-playlist", "-J", "ytsearch15:" + q], timeout=60))
        out = []
        for e in res.get("entries") or []:
            if not e or not ID_RE.match(str(e.get("id", ""))):
                continue
            out.append(dict(id=e["id"], title=e.get("title") or "", author=e.get("channel") or e.get("uploader") or "",
                            seconds=int(e.get("duration") or 0), views=int(e.get("view_count") or 0)))
        return out

    def info(self, vid):
        if vid in self.info_cache:
            return self.info_cache[vid]
        if self.demo:
            n = int(vid[4:] or 1) if vid.startswith("demo") else 1
            d = dict(id=vid, title="Demo clip %d" % n, author="TubeME demo", seconds=8 + n * 4, views=1000 * n,
                     published="2026-01-01", description="A generated test pattern with a tone.\nNo internet involved.")
        else:
            # details don't need a playable format, so a format problem must not hide them
            e = json.loads(self.tools.ytdlp(["-J", "--skip-download", "--ignore-no-formats-error",
                                             "https://www.youtube.com/watch?v=" + vid], timeout=90))
            up = e.get("upload_date") or ""
            d = dict(id=vid, title=e.get("title") or "", author=e.get("channel") or e.get("uploader") or "",
                     seconds=int(e.get("duration") or 0), views=int(e.get("view_count") or 0),
                     published=("%s-%s-%s" % (up[:4], up[4:6], up[6:8])) if len(up) == 8 else "",
                     description=(e.get("description") or "")[:1500])
        self.info_cache[vid] = d
        return d

    def thumb(self, vid, width):
        width = 320 if width >= 320 else 120
        path = os.path.join(self.thumb_dir, "%s-%d.jpg" % (vid, width))
        if os.path.exists(path):
            return path
        raw = os.path.join(self.thumb_dir, vid + "-raw.jpg")
        if not os.path.exists(raw):
            if self.demo:
                r = run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                         "testsrc2=size=320x180:rate=1", "-frames:v", "1", raw])
                if r.returncode:
                    raise RuntimeError("thumbnail: " + r.stderr.decode("utf-8", "replace")[-200:])
            else:
                with urllib.request.urlopen("https://i.ytimg.com/vi/%s/mqdefault.jpg" % vid, timeout=20) as u:
                    data = u.read()
                with open(raw, "wb") as f:
                    f.write(data)
        # baseline JPEG at the size the phone asked for: small heaps can't decode big or progressive ones
        r = run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", raw, "-vf", "scale=%d:-2" % width,
                 "-q:v", "6", "-frames:v", "1", path])
        if r.returncode:
            raise RuntimeError("thumbnail: " + r.stderr.decode("utf-8", "replace")[-200:])
        return path

    def source(self, vid, job):
        """The original video on disk, downloaded once however many conversions want it."""
        with self.lock:
            lk = self.src_locks.setdefault(vid, threading.Lock())
        with lk:
            for name in os.listdir(self.src_dir):
                if name.startswith(vid + ".") and not name.endswith(".part") and not re.search(r"\.f\d+\.", name):
                    return os.path.join(self.src_dir, name)
            job["state"] = "fetching"
            if self.demo:
                n = int(vid[4:] or 1) if vid.startswith("demo") else 1
                path = os.path.join(self.src_dir, vid + ".mp4")
                r = run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                         "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                         "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                         "-t", str(8 + n * 4), "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                         "-c:a", "aac", "-ac", "2", path])
                if r.returncode:
                    raise RuntimeError("demo source: " + r.stderr.decode("utf-8", "replace")[-300:])
                return path
            out = self.tools.ytdlp(["-f", SOURCE_FORMAT, "--merge-output-format", "mkv",
                                    "-o", os.path.join(self.src_dir, "%(id)s.%(ext)s"),
                                    "--print", "after_move:filepath", "--no-simulate",
                                    "https://www.youtube.com/watch?v=" + vid])
            lines = [l.strip() for l in out.splitlines() if l.strip()]
            if lines and os.path.isfile(lines[-1]):
                return lines[-1]
            for name in os.listdir(self.src_dir):
                if name.startswith(vid + ".") and not name.endswith(".part") and not re.search(r"\.f\d+\.", name):
                    return os.path.join(self.src_dir, name)
            raise RuntimeError("yt-dlp finished but left no file")

    # ---------- conversion ----------

    def prepare(self, vid, key):
        """Starts a conversion if needed and reports where it stands."""
        name = "%s-%s.%s" % (vid, key, PROFILES[key]["ext"])
        out = os.path.join(self.out_dir, name)
        with self.lock:
            job = self.jobs.get(name)
            if job is None or (job["state"] == "error" and time.time() - job["at"] > 30):
                if os.path.exists(out) and job is None:
                    job = dict(state="ready", percent=100, at=time.time())
                else:
                    job = dict(state="queued", percent=0, at=time.time(), error="")
                    threading.Thread(target=self.convert, args=(vid, key, out, job), daemon=True).start()
                self.jobs[name] = job
        if job["state"] == "ready":
            os.utime(out, None)  # mark as recently used for the cache clean-up
            return dict(state="ready", url="/file/" + name, bytes=os.path.getsize(out))
        if job["state"] == "error":
            return dict(state="error", error=job["error"])
        return dict(state=job["state"], percent=job["percent"])

    def convert(self, vid, key, out, job):
        p = PROFILES[key]
        tmp = out + ".part"
        try:
            src = self.source(vid, job)
            with self.slots:
                job["state"] = "converting"
                dur, sw, sh = self.probe(src)
                cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-nostdin", "-i", src]
                if p.get("copy") and self.codecs(src) == ("h264", "aac"):
                    cmd += ["-c", "copy"]
                elif p.get("copy"):
                    # YouTube had no ready-made H.264 file for this one: make an equivalent
                    cmd += ["-vf", "scale=%d:%d,format=yuv420p" % fit(sw, sh, 640, 360), "-c:v", "libx264",
                            "-profile:v", "baseline", "-level", "3.0", "-preset", "veryfast", "-b:v", "600k",
                            "-c:a", "aac", "-ar", "44100", "-ac", "2", "-b:a", "96k"]
                else:
                    bw, bh = p["box"]
                    if p.get("fixed"):
                        vf = ("scale=%d:%d:force_original_aspect_ratio=decrease,"
                              "pad=%d:%d:(ow-iw)/2:(oh-ih)/2" % (bw, bh, bw, bh))
                    else:
                        vf = "scale=%d:%d" % fit(sw, sh, bw, bh)
                    cmd += ["-vf", vf + ",format=yuv420p", "-r", str(p["fps"])] + p["video"] + self.audio(p["audio"])
                cmd += ["-movflags", "+faststart", "-f", p["ext"], "-progress", "pipe:1", tmp]
                log("converting", vid, "to", key)
                t0 = time.time()
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                err = []
                threading.Thread(target=lambda: err.append(proc.stderr.read()), daemon=True).start()
                for line in proc.stdout:
                    line = line.decode("ascii", "replace").strip()
                    if line.startswith("out_time_ms=") and dur > 0:
                        try:
                            job["percent"] = max(0, min(99, int(int(line[12:]) / 10000 / dur)))
                        except ValueError:
                            pass
                if proc.wait():
                    time.sleep(0.1)
                    raise RuntimeError("ffmpeg: " + (err[0] if err else b"").decode("utf-8", "replace").strip()[-300:])
            os.replace(tmp, out)
            job.update(state="ready", percent=100, at=time.time())
            log("ready", os.path.basename(out), "%d KB in %.1f s" % (os.path.getsize(out) // 1024, time.time() - t0))
            self.trim_cache()
        except Exception as e:  # reported to the phone, which shows it
            msg = short(e)
            job.update(state="error", error=msg, at=time.time())
            log("failed", vid, key, "-", msg)
            try:
                os.remove(tmp)
            except OSError:
                pass

    def audio(self, kind):
        if kind == "narrow":
            if self.has_amr:
                return ["-c:a", "libopencore_amrnb", "-ar", "8000", "-ac", "1", "-b:a", "12.2k"]
            return ["-c:a", "aac", "-ar", "16000", "-ac", "1", "-b:a", "24k"]
        if kind == "low":
            return ["-c:a", "aac", "-ar", "22050", "-ac", "1", "-b:a", "32k"]
        if kind == "mid":
            return ["-c:a", "aac", "-ar", "44100", "-ac", "2", "-b:a", "64k"]
        return ["-c:a", "aac", "-ar", "44100", "-ac", "2", "-b:a", "96k"]

    def codecs(self, src):
        r = run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", src])
        st = json.loads(r.stdout.decode("utf-8", "replace") or "{}").get("streams") or []
        v = [x["codec_name"] for x in st if x.get("codec_type") == "video"]
        a = [x["codec_name"] for x in st if x.get("codec_type") == "audio"]
        return (v[0] if v else None, a[0] if a else None)

    def probe(self, src):
        r = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                 "stream=width,height:format=duration", "-of", "json", src])
        d = json.loads(r.stdout.decode("utf-8", "replace") or "{}")
        st = (d.get("streams") or [{}])[0]
        return float((d.get("format") or {}).get("duration") or 0), int(st.get("width") or 640), int(st.get("height") or 360)

    def trim_cache(self):
        """Deletes the least recently used files once the cache is over its size."""
        files = []
        for d in (self.out_dir, self.src_dir):
            for n in os.listdir(d):
                path = os.path.join(d, n)
                if n.endswith(".part") or not os.path.isfile(path):
                    continue
                files.append((os.path.getmtime(path), os.path.getsize(path), path))
        total = sum(f[1] for f in files)
        for _, size, path in sorted(files):
            if total <= self.max_cache:
                break
            try:
                os.remove(path)
                total -= size
                with self.lock:
                    self.jobs.pop(os.path.basename(path), None)
                log("cache full: removed", os.path.basename(path))
            except OSError:
                pass


class Handler(BaseHTTPRequestHandler):
    server_version = "TubeME/" + VERSION
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def fail(self, code, msg):
        self.send_json({"error": msg}, code)

    def send_file(self, path, ctype):
        size = os.path.getsize(path)
        start, end, code = 0, size - 1, 200
        m = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", "").strip())
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            else:
                start = max(0, size - int(m.group(2)))
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.end_headers()
                return
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(chunk)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        s = self.server.app
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        p = u.path
        try:
            if p == "/tubeme":
                return self.send_json({"tubeme": 1, "version": VERSION, "demo": s.demo, "qualities": s.qualities,
                                       "sound3gp": "amr" if s.has_amr else "aac"})
            if p == "/search":
                if not q.get("q", "").strip():
                    return self.fail(400, "q is required")
                return self.send_json(s.search(q["q"].strip()))
            vid = q.get("id", "")
            if p in ("/info", "/thumb", "/prepare") and not ID_RE.match(vid):
                return self.fail(400, "id must be an 11 character video id")
            if p == "/info":
                return self.send_json(s.info(vid))
            if p == "/thumb":
                try:
                    w = int(q.get("w", "320"))
                except ValueError:
                    w = 320
                return self.send_file(s.thumb(vid, w), "image/jpeg")
            if p == "/prepare":
                key = q.get("q", "")
                if key not in s.qualities:
                    return self.fail(404, "this server doesn't make quality '%s'" % key)
                return self.send_json(s.prepare(vid, key))
            if p.startswith("/file/"):
                name = p[6:]
                path = os.path.join(s.out_dir, name)
                if not FILE_RE.match(name) or not os.path.isfile(path):
                    return self.fail(404, "no such file (it may have been cleared from the cache; press Play again)")
                return self.send_file(path, "video/3gpp" if name.endswith(".3gp") else "video/mp4")
            if p == "/":
                body = ("<html><body><h1>TubeME server %s</h1><p>Running%s. Qualities: %s.</p>"
                        "<p>In TubeME: Settings &gt; My TubeME server &gt; this page's address.</p></body></html>"
                        % (VERSION, " in demo mode" if s.demo else "", ", ".join(s.qualities))).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
                return
            self.fail(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            msg = short(e)
            log("error on", self.path, "-", msg)
            try:
                self.fail(502, msg)
            except Exception:
                pass


def lan_addresses(port):
    out = []
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("10.255.255.255", 1))  # sends nothing; just picks the outgoing interface
        out.append(sock.getsockname()[0])
        sock.close()
    except OSError:
        pass
    try:
        for a in socket.gethostbyname_ex(socket.gethostname())[2]:
            if a not in out and not a.startswith("127."):
                out.append(a)
    except OSError:
        pass
    return ["http://%s:%d" % (a, port) for a in out] or ["http://127.0.0.1:%d" % port]


def main():
    ap = argparse.ArgumentParser(description="TubeME server: converts YouTube videos for old phones.")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0", help="address to listen on (default: every network)")
    ap.add_argument("--cache", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache"))
    ap.add_argument("--max-cache-mb", type=int, default=2000, help="delete the oldest files past this size")
    ap.add_argument("--workers", type=int, default=2, help="conversions running at the same time")
    ap.add_argument("--demo", action="store_true", help="no YouTube: generated test videos only")
    ap.add_argument("--no-update", action="store_true",
                    help="don't install or update yt-dlp, and don't fetch Deno")
    a = ap.parse_args()
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            how = ("winget install Gyan.FFmpeg" if os.name == "nt" else "brew install ffmpeg" if sys.platform == "darwin"
                   else "sudo apt install ffmpeg  (or your distribution's equivalent)")
            sys.exit("%s was not found. Install ffmpeg (%s) and make sure it is on the PATH." % (tool, how))
    tools = None
    if not a.demo:
        tools = Tools(os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools"), not a.no_update)
        tools.update("starting up", force=True)
        if tools.ytdlp_version() is None:
            sys.exit("yt-dlp is missing%s. Install it with:  %s -m pip install -U \"yt-dlp[default]\"   "
                     "(or run with --demo to test without it)"
                     % (" and --no-update is set" if a.no_update else "", os.path.basename(sys.executable)))
        log("yt-dlp %s, JavaScript runtime: %s" % (tools.ytdlp_version(),
            "none" if not tools.js else tools.js[0] + (" (%s)" % tools.js[1] if tools.js[1] else "")))

        def keep_current():
            while True:
                time.sleep(UPDATE_EVERY)
                tools.update("12 hours since the last check", force=True)
        if not a.no_update:
            threading.Thread(target=keep_current, daemon=True).start()
    ff = run(["ffmpeg", "-version"]).stdout.decode("utf-8", "replace").splitlines()
    log(ff[0] if ff else "ffmpeg version unknown")
    app = Server(a.cache, a.demo, a.max_cache_mb, a.workers, tools)
    httpd = ThreadingHTTPServer((a.host, a.port), Handler)
    httpd.daemon_threads = True
    httpd.app = app
    log("TubeME server %s%s, qualities: %s" % (VERSION, " (demo mode)" if a.demo else "", ", ".join(app.qualities)))
    log("cache:", app.cache, "(up to %d MB)" % a.max_cache_mb)
    for addr in lan_addresses(a.port):
        log("in TubeME, set My TubeME server to", addr)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("stopped")


if __name__ == "__main__":
    main()
