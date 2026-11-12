# Changelog

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
