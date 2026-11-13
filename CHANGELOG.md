# Changelog

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
