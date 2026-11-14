# Changelog

## 0.4.1 and server 0.6.1

- **Your server is never written off**: if it failed once (a check at start-up before Wi-Fi was
  up, a slow answer), the app used to skip it for two minutes and go straight to the public
  servers. Your own TubeME server and your own YtAPILegacy server are now always asked first,
  with their full waiting time, and the screen says why when the app moves on from it
- **Many more services**: Piped servers are supported (search, Popular, related, channels,
  comments, 360p), and the app has built-in Invidious (including S60Tube, `s60tube.io.vn`),
  Piped, yt2009 and YtAPILegacy servers that stay in the list whatever a downloaded list says
- **Several server lists, merged**: your own server's new `/servers` (plain http, it fetches the
  https lists for the phone), the list URL from Settings, and `servers.json` in this repository.
  One list being down costs nothing
- **Server: when YouTube refuses your computer** ("Sign in to confirm you're not a bot"), the
  server now gets the 360p file through an Invidious or Piped server (S60Tube first) and converts
  that, so playing still works
- **Faster video**: the app asks your server to start on a video as soon as its page opens, so
  Play usually finds it ready. When YouTube refuses the server, all mirrors are asked at once
  instead of one by one (dead ones used to cost 20 s each), and a refusal no longer triggers a
  pointless yt-dlp update first. yt-dlp fetches in 4 parallel pieces. The server window shows how
  long each fetch took
- Server: every request is logged (`GET /search?q=... -> 200 in 3.1 s`), so you can see the
  phone reaching it
- End-to-end test in CI: the phone's own code against a real server and real YouTube, every
  quality played; public services reported

## 0.4.0 and server 0.6.0

- **Public servers**: the server can be hosted for other people. `--key` makes it friends-only
  (addresses end in `/k/KEY`); `--public` adds limits per visitor: requests a minute, new videos an
  hour, videos at once, longest video, connections. Each limit can be set on its own and `0`
  turns it off, so the length cap is optional (`--public --max-minutes 0`). `--trust-proxy` for
  running behind nginx or Caddy. See "Hosting it for other people" in `server/README.md`
- The app uses more than one TubeME server: yours first, then public ones from the server list
  (a new `tubeme` key in the list file), ranked by speed with the other public servers
- A server's own reason for refusing ("too many requests; try again in 42 s", "this server needs
  a key") reaches the screen instead of "HTTP 429"

## 0.3.0 and server 0.5.0

- **More results**: search, Popular, related videos and channels end with "More results...", which
  loads the next page into the same list. Later pages come from the server that gave the first,
  since every kind of server pages differently
- **More comments**: the comments screen has a "More" command while there are more to load
- **Related videos** and **Channel** (the uploader's videos) in the video menu
- **Thumbnails in lists**: Settings > "Thumbnails in lists" puts a small thumbnail next to each
  result, as the phone's own list icon. Off by default (it is one request per row)
- **Back goes back**: search > video > related > video > Back returns to the related list, then
  to the first video, and so on
- **More than one server again**: when the shared server list failed to load (no network at the
  first start), the app knew only its one built-in server until the next start. It now retries
  a minute later, and whenever it knows only that one
- Server: `/search`, `/popular` and `/comments` take a page number, and there is a new `/channel`

## 0.2.4

- Fixed the app freezing (and then refusing to start) when saving the Log, or saving anything, while
  the start-up server test was running: two permission prompts at the same moment could lock up
  the phone's Java. Connections and files are now opened one at a time, so only one prompt can
  ever be on screen

## 0.2.3 and server 0.4.1

- Fixed searching and Popular getting stuck on "Searching...": a server that sent its headers and
  then stalled could hold a search forever (only the wait for the first answer had a time limit,
  not the reading). Every request now has a limit for the whole exchange
- Fixed the same hang spreading: while the server-list update hung at start-up, every search
  waited for it. Searches now go ahead with the list they have
- Your own server gets 75 s for search, details and comments (it runs yt-dlp for them)
- The wait screen says which server is being asked and why one was skipped
- The Log explains Symbian's numeric network errors (-1, -34, -191, -5120...)
- Popular works from your own server (new `/popular` address, using YouTube's "hype" charts since
  the Trending page is gone). A server without it is skipped for Popular without being marked down

## 0.2.2 and server 0.4.0

- Comments: a "Comments" entry in the video menu shows up to 30 top comments with author, likes
  and age. They come from your own server (new `/comments` address), YtAPILegacy or Invidious,
  whichever answers first

## 0.2.1

- Settings > "Offer every quality": lists the qualities the app otherwise hides on a phone (H.264
  360p on S60 3rd and 5th Edition), so the phone can be tried instead of trusting a spec sheet

## Server 0.3.0 (app unchanged)

- Runs on macOS and Linux as well as Windows, and is tested on all three for every change
- yt-dlp is installed into a `tools` folder next to the script instead of the system Python, so it
  works where system-wide `pip install` is refused (macOS with Homebrew, Debian, Ubuntu, Fedora)
- The Deno download shows progress every few seconds, gives up on a stalled connection after 30 s
  and retries, instead of sitting silent
- Setup hints per system when ffmpeg or pip is missing

## Server 0.2.0 (app unchanged)

- Fixed "Requested format is not available" on every video: YouTube gives yt-dlp no video formats
  unless a JavaScript runtime is present. The server now uses Deno, and downloads it if missing
- The server installs and updates yt-dlp by itself: at start, every 12 hours, and after a failed
  request (which is then retried). yt-dlp runs as a separate program, so updates apply at once
- Wider choice of source formats (picture and sound fetched separately and joined when YouTube has
  no single 360p file); H.264 360p is converted when the source isn't H.264
- yt-dlp's own warnings are shown in the server window, and the phone gets a plain explanation

## 0.2.0

- **TubeME server** (`server/tubeme_server.py`): a small Python server for your own PC that fetches,
  converts and serves videos for the phone, with search, details and thumbnails. See `server/README.md`
- New qualities, made by that server: MPEG-4 240p and H.264 240p (inside 320x240)
- Settings > "My TubeME server": when set it is asked first for everything; the public servers
  remain as backup. The app reads which qualities it makes and shows conversion progress in percent

## 0.1.13

- Waits scale with the video's length: a yt2009 server gets 90 s plus 1 s per 6 s of video, a
  YtAPILegacy conversion 5 min plus 1 s per 3 s of video, both capped at 15 minutes. The wait
  screen says how long it will wait
- YtAPILegacy is not asked to convert videos of 55 minutes or more (it would send H.264 instead)

## 0.1.12

- A yt2009 server that sits on a request no longer holds everything up: if the first server hasn't
  answered after 15 s the next one is asked as well, and whichever answers first is used
- The wait screen shows a running "Still waiting... 20 s" line during long waits
- A server that used up the whole 90 s for a quality is asked last for that quality for the rest of
  the session

## 0.1.11

- "Phone's player/browser" is back in the Symbian playback list (0.1.10 had removed it)

## 0.1.10

- "Phone's player/browser" is no longer offered on Symbian, where the app can always download by
  itself; a saved setting of it becomes Auto. It remains the automatic fallback when nothing can
  be saved (no card, or file access refused)
- "RealPlayer" streaming is no longer offered on phones that aren't Symbian

## 0.1.9

- On Symbian the playback modes are named "RealPlayer, stream" and "RealPlayer, download first"
- Servers differ in which qualities they have. The app now remembers what each server said it
  doesn't offer, marks qualities with no working server as "(no server)" in the quality lists, and
  when the chosen quality isn't available it asks which of the available ones to use (showing how
  many servers have each) and carries on with the play or download

## 0.1.8

- Two 144p qualities, both from yt2009 servers: "H.264 144p" (256x144 MP4, within what S60 3rd/5th
  Edition can decode) and "H.263 144p (3GP)" (176x144, 10 fps, the smallest files)
- MPEG-4 360p falls back to a yt2009 server's plain /http_mpeg4 when it has no /http_mpeg4_360
  (servers differ by version)
- Clearer server messages: "doesn't offer this quality", "needs a sign-in", and "still preparing
  the video; try again in a minute" when a conversion outlasts the 90 s wait

## 0.1.7

- H.264 360p is no longer offered on S60 3rd and 5th Edition (it plays as sound only there); a saved
  H.264 setting moves to MPEG-4 360p. Still offered on Symbian^3 and later and on other phones
- Fixes from a code review:
  - a download that ran out of servers repeated the whole round of servers (and their 90 s waits)
    once per server, and a server could be tried twice in one attempt
  - a download cut off part-way was reported as saved; it now fails and removes the partial file
  - an error page sent in place of a video was saved as the video
  - a failed re-download could delete an earlier complete copy of the same video
  - a failed server-list update was recorded as done, blocking retries for three days
  - search, video info, thumbnails and downloads had no time limit (now 30 s, 60 s for a ready
    file, 5 minutes for a YtAPILegacy conversion)
  - the crash log stopped being written after 150 lines; it now keeps rolling
  - a server whose search failed was also refused for video, reported as "unreachable"
  - the pre-play reachability check had no time limit and could wrongly mark a server down

## 0.1.6

- Default quality on Symbian is MPEG-4 360p: per Nokia's specs, S60 3rd/5th Edition phones decode
  MPEG-4 up to VGA/nHD but H.264 only up to QVGA (README has the table)
- Choosing H.264 360p on such a phone shows a note on the wait screen that it will likely be sound
  without picture

## 0.1.5

- The wait screen lists what is happening line by line: which server is being asked, failures and
  the switch to the next server, then "Downloading 5%", "10%"... (every 256 KB when the server
  gives no size)
- A server that never answers no longer hangs a download forever: yt2009 gets 90 s to get a video
  ready, a ping gets 8 s, then the next server is tried
- Bigger thumbnails (320x180) from Invidious servers on screens 320 px or wider
- The start-up server test checks every video server but stops after 5 working Invidious servers
  (the list has dozens); Servers > Test still checks all of them

## 0.1.4

- Servers are used in order of reply time from the last test (fastest first, untested next, down
  last), and the Servers screen is sorted the same way

## 0.1.3

- Server test on every start: each known server is checked from the phone, unreachable ones go to the
  back of the queue. Menu > Servers shows each one as ok (with reply time), down or not tested, and
  can test again or update the list
- Before a streaming link goes to a player the app opens it itself and reads the first bytes; a bad
  link is skipped for the next server. The log records the content type, size, range support and
  whether the file can be played while it downloads (boxes=ftyp,moov) or only once complete (ftyp,mdat)

## 0.1.2

- The wait screen is one plain text form reused for every task (no animated gauges). The app being
  closed by the phone after a few tasks on the C6-00 pointed at those
- Servers that fail are remembered for the session and tried last; a YtAPILegacy server is checked
  for reachability before its video link is handed to a player (an unreachable one crashed RealPlayer)
- .ram files rotate through four names so a file RealPlayer still holds is never replaced
- Auto playback is now "Download, then play" wherever there is file access, Symbian included
- Thumbnails no longer depend on a heap check that was wrong on S60
- More log lines: download progress, HTTP errors, free memory

## 0.1.1

- Log screen in the main menu: a trace of what the app did (servers, stream URL, folder probing, player
  steps, every error with its exception class). It is written as it happens, so after a crash the next
  start shows it under "Previous session". "To file" saves it as tubeme-log.txt
- Thumbnails are drawn by the app now, so they show on phones that hide image items in forms (C6-00)

## 0.1.0

- First build: search, popular, open by ID or link, video page with thumbnail
- Servers from the shared notPipe instance list (YtAPILegacy, yt2009, Invidious), random pick with failover
- Playback: RealPlayer through a .ram file on Symbian, download then play, in-app MMAPI, or the phone's own player
- Quality: H.263 288p 3GP, MPEG-4 270p, MPEG-4 360p, H.264 360p
