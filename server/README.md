# TubeME server

A small server you run on your own computer (Windows, macOS or Linux). It fetches a YouTube video,
converts it to something an old phone can play, and hands it to TubeME. It also answers search,
video details thumbnails and comments, so TubeME works with it alone.

What it adds over the public servers:

- **240p** (MPEG-4 or H.264 inside 320x240), the natural size for QVGA phones
- **144p** in H.264 (256x144) and H.263 3GP (176x144)
- it tells the app exactly which qualities it makes, and reports conversion progress in percent
- picture sizes are always multiples of 16, which some phone decoders insist on

## Setup

You need **Python 3.10 or newer** and **ffmpeg**. The server installs the rest itself.

**Windows**

1. Install Python 3 from python.org. Tick "Add python.exe to PATH" in the installer.
2. `winget install Gyan.FFmpeg`, then open a new terminal so ffmpeg is on the PATH.
3. `python tubeme_server.py`
4. Windows Firewall will ask: allow it on **private** networks.

**macOS**

1. Install [Homebrew](https://brew.sh) if you don't have it, then `brew install python ffmpeg`
2. `python3 tubeme_server.py`
3. macOS will ask whether Python may accept incoming connections: allow it.

**Linux**

1. Debian/Ubuntu: `sudo apt install python3 python3-pip ffmpeg`
   Fedora: `sudo dnf install python3 python3-pip ffmpeg` (ffmpeg needs RPM Fusion enabled)
   Arch: `sudo pacman -S python python-pip ffmpeg`
2. `python3 tubeme_server.py`
3. If a firewall is on, open the port: `sudo ufw allow 8765/tcp` (or your firewall's equivalent).

**Then, on any system**

The server prints a line like `in TubeME, set My TubeME server to http://192.168.1.20:8765`.
Type that address into TubeME: Settings > My TubeME server.

The first start takes longer: the server installs yt-dlp and downloads Deno (about 45 MB, with a
progress line every few seconds).

The phone has to be on the same Wi-Fi as the computer, and the server has to be running.

To check the phone can reach the computer before involving YouTube, run it with `--demo`: it serves
generated test videos and needs no internet.

## What it keeps up to date by itself

Everything it installs goes into a `tools` folder next to the script. Nothing is installed
system-wide, so it also works on systems that don't allow `pip install` into the system Python
(macOS with Homebrew, Debian, Ubuntu, Fedora).

- **yt-dlp**, the part that talks to YouTube. Installed or updated at every start, every 12 hours
  while running, and once more whenever a request fails (then the request is retried). An update
  takes effect immediately, no restart needed.
- **Deno**, a JavaScript runtime. YouTube makes yt-dlp solve a JavaScript puzzle before it shows any
  video formats; without a runtime every video fails with "Requested format is not available". If
  Deno isn't installed the server downloads it from Deno's GitHub releases, and refreshes that copy
  every 30 days. An installed Deno, Node or Bun is used if found.

**ffmpeg is not updated by the server.** It changes rarely; your package manager does it
(`winget upgrade Gyan.FFmpeg`, `brew upgrade ffmpeg`, `sudo apt upgrade`).

`--no-update` turns all of this off (nothing is installed, updated or downloaded).

## When YouTube refuses your computer

YouTube sometimes answers a computer with "Sign in to confirm you're not a bot", mostly
servers in data centres but home connections too. The server then gets the 360p file through
an Invidious or Piped server instead (S60Tube first, then the ones in the shared lists) and
converts that, and fills in missing video details the same way. You'll see `trying mirrors`
and `got ... from ...` in the window when it happens.

## What you see in the window

One line per request from the phone, like `192.168.1.40 GET /search?q=nokia -> 200 in 2.1 s`
(repeated progress checks and thumbnails are left out). If nothing shows up when you search on
the phone, the phone isn't reaching the server: check the address in TubeME's settings, the
Wi-Fi, and the firewall.

The server also hands the phone every public server list it can reach at `/servers`, over plain
http, so the phone gets lists that are only on https too.

## Options

    --port 8765            port to listen on
    --cache <folder>       where downloads and conversions are kept (default: cache/ next to the script)
    --max-cache-mb 2000    oldest files are deleted past this size
    --workers 2            conversions running at once
    --demo                 test videos only, no YouTube
    --no-update            don't install or update yt-dlp, don't fetch Deno
    --public               limits for a server open to anyone (see "Hosting it for other people")
    --key KEY              friends only: addresses must end in /k/KEY
    --max-minutes N        longest video converted; 0 = no limit
    --trust-proxy          behind nginx/Caddy: count visitors by X-Forwarded-For

## Hosting it for other people

The same script can serve people outside your home, either friends or anyone.

**Friends only:** start it with a key, for example `python3 tubeme_server.py --key 8xQ2mv7c`. Every
address then has to end in `/k/8xQ2mv7c`, so the address you give your friends is
`http://<your address>:8765/k/8xQ2mv7c`. They type that into Settings > My TubeME server. Anyone
without the key gets nothing but "this server needs a key".

**Anyone:** start it with `--public`. That switches on limits per visitor, so one person can't
take the whole machine:

| Option | `--public` default | What it limits |
|---|---|---|
| `--per-minute` | 90 | requests a minute (downloads don't count) |
| `--conversions-per-hour` | 20 | new videos a visitor can have converted per hour; videos already converted are free |
| `--at-once` | 2 | videos one visitor can have converting at the same time |
| `--max-minutes` | 30 | the longest video that will be converted |
| `--max-connections` | 64 | connections served at the same moment |

Each can be changed, and `0` turns that one off: `--public --max-minutes 0` keeps the other limits
but converts videos of any length, `--public --max-minutes 90` allows up to an hour and a half.
They also work without `--public`, for example a home server with just `--max-minutes 60`. The
server prints the limits it is using at startup and shows them on its front page.

`--public` and `--key` can be combined.

**Getting it onto the internet**

- On a home connection: forward the port (8765 by default) on your router to the computer running
  the server, and give people your public address. Many home connections change address now and
  then; a free dynamic DNS name (DuckDNS, No-IP) keeps one name pointing at it.
- On a rented server (VPS): open the port in its firewall and run it as a service (below).
- Behind nginx or Caddy: add `--trust-proxy`, so the limits count each visitor and not the proxy.
  Without it every visitor looks like the proxy and they all share one set of limits.
- Old phones can't do modern HTTPS, so the phone side has to stay plain `http://`. A proxy can
  offer HTTPS to other clients alongside, but TubeME on an old phone will use the http address.

**Letting TubeME find it**

TubeME reads a list of servers from the "Server list URL" in its Settings. A list is a JSON file;
public TubeME servers go under a `tubeme` key, next to the existing ones:

    {"tubeme": ["http://tube.example.org:8765"], "ytapilegacy": [...], "yt2009": [...], "invidious": [...]}

Anyone can host such a file and point TubeME at it.

**Before opening it to everyone**

- YouTube blocks addresses that download a lot. A busy public server may find YouTube refusing it
  after a while; rented servers in data centres are refused sooner than home connections.
- Downloading from YouTube is against YouTube's terms of service, and you are the one serving the
  files. Keep the cache small (`--max-cache-mb`) and think about who you open it to.
- Each conversion costs CPU. A small VPS manages two at once comfortably (`--workers 2`).

## Running it all the time (Linux)

A systemd unit, saved as `/etc/systemd/system/tubeme.service` (adjust the user and path):

    [Unit]
    Description=TubeME server
    After=network-online.target

    [Service]
    User=you
    ExecStart=/usr/bin/python3 /home/you/tubeme/tubeme_server.py
    Restart=on-failure

    [Install]
    WantedBy=multi-user.target

Then `sudo systemctl enable --now tubeme`. It runs fine on a Raspberry Pi 4 or newer, just slower.

## Qualities

| Key | What | Container |
|---|---|---|
| h263-144 | H.263 176x144, 15 fps | 3GP |
| h264-144 | H.264 Baseline, inside 256x144 | MP4 |
| mp4v-240 | MPEG-4 Visual, inside 320x240 | MP4 |
| h264-240 | H.264 Baseline, inside 320x240 | MP4 |
| h263-288 | H.263 352x288, 20 fps | 3GP |
| mp4v-270 | MPEG-4 Visual, inside 480x272 | MP4 |
| mp4v-360 | MPEG-4 Visual, inside 640x360 | MP4 |
| h264-360 | YouTube's own 360p file when it is H.264, otherwise converted | MP4 |

3GP files get AMR sound when ffmpeg has the AMR encoder (`libopencore_amrnb`) and AAC otherwise. The
server says which at startup. The ffmpeg from Debian and Ubuntu has no AMR encoder. AAC is fine on
Symbian; very old phones may play AAC 3GP files silent.

## Things to know

- Without `--key` there is no password: anyone who can reach the port can use it. At home behind a
  router that's only your own network; see "Hosting it for other people" before forwarding the port.
- When a video fails, the server window shows yt-dlp's own messages just above the "failed" line.
- `python test_server.py` checks the server in demo mode; add `--online` to also test a real first
  start (installs yt-dlp and fetches Deno into a temporary folder).
- The automated tests run on Linux, macOS and Windows for every change.
