Stability build after the first C6-00 test. Playback is still unconfirmed on a real phone.

- Plain reused wait screen instead of animated gauges (suspected cause of the app being closed on the C6-00)
- Unreachable servers are skipped, and a YtAPILegacy server is checked before its link goes to a player
- .ram files rotate names so RealPlayer's open file is never replaced
- Auto playback downloads first wherever there is file access
- Thumbnails work on S60 heaps; more detail in the Log screen
