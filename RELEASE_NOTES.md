First public build of TubeME, a YouTube client for J2ME phones (MIDP 2.0 / CLDC 1.1), plus a small server you can run on your own PC.

**Downloads**
- `TubeME-0.2.1.zip`: the app (jar + jad). Also attached separately as `TubeME.jar` / `TubeME.jad`
- `TubeME-server-0.3.0.zip`: the optional server for Windows, macOS or Linux (Python script + setup guide)

**The app**
- Search, popular, open by ID or link, video page with thumbnail
- Uses public YtAPILegacy, yt2009 and Invidious servers from a shared list: tested from the phone at every start, fastest first, with failover
- Playback on Symbian: RealPlayer streaming, download then RealPlayer, in-app player, or the phone's browser. Other phones: download then play, in-app (MMAPI), or the phone's player
- Qualities from H.263 144p 3GP up to H.264 360p; when a server lacks the chosen one the app asks which available quality to use
- A Log screen that survives crashes, for bug reports

**The server** (`server/README.md`)
- Fetches, converts and serves video sized for phones, including 240p and 144p, with search and thumbnails
- Needs Python and ffmpeg; installs and updates yt-dlp and Deno by itself
- Set its address in Settings > My TubeME server; the public servers stay as backup

**Tested**
- On a Nokia C6-00 (S60 5th Edition) with the public servers: search, thumbnails, downloading, RealPlayer mode, the quality picker
- Not yet confirmed on other phones
- The server: converting real YouTube videos on Windows; its automated tests pass on Linux and Windows (macOS not yet run)

**Known limits**
- S60 3rd and 5th Edition are listed as decoding H.264 only up to 320x240, so H.264 360p is hidden there unless "Offer every quality" is ticked
- Public servers differ in which qualities they have, and some conversions never answer
- The app is unsigned: expect permission prompts
