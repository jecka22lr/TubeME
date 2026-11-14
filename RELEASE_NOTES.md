TubeME, a YouTube client for J2ME phones (MIDP 2.0 / CLDC 1.1), plus a small server you can run on your own computer.

**Downloads**
- `TubeME-0.4.1.zip`: the app (jar + jad). Also attached separately as `TubeME.jar` / `TubeME.jad`
- `TubeME-server-0.6.1.zip`: the optional server for Windows, macOS or Linux (Python script + setup guide)

**What's new in 0.4.1 / server 0.6.1**
- Your own server is always asked first and given its full time. Before, one failed check (say, at start-up before Wi-Fi was up) made the app skip it for two minutes and go straight to public servers
- When YouTube refuses your computer ("Sign in to confirm you're not a bot"), the server gets the video through Invidious or Piped servers instead and converts that
- Piped servers are supported, and Invidious (including S60Tube), Piped, yt2009 and YtAPILegacy servers are built in, so the app never ends up knowing only one server
- Server lists are merged from three places: your server's `/servers` (plain http), the list URL in Settings, and `servers.json` in this repository
- The server prints one line per request, so you can see the phone reaching it

**Tested**
- Every push now runs an end-to-end test on GitHub: the app's own code against a real server and real YouTube, searching, paging, details, comments and playing four qualities, plus a report on every public server
- The server's tests pass on Linux, macOS and Windows
- Earlier versions were tested on a Nokia C6-00; this one hasn't been on a phone yet

**Known limits**
- S60 3rd and 5th Edition are listed as decoding H.264 only up to 320x240, so H.264 360p is hidden there unless "Offer every quality" is ticked
- Most public Piped and Invidious servers block or have shut down; the built-in ones are those that answered on release day
- The app is unsigned: expect permission prompts
