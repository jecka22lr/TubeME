# Changelog

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
