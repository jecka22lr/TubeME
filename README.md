# TubeME

YouTube client for J2ME (MIDP 2.0 / CLDC 1.1). Early: 0.1.0 has not been confirmed on a real phone yet.

Uses the same kinds of servers as [notPipe](https://github.com/gohoski/notPipe): YtAPILegacy, yt2009 and
Invidious, picked at random from a shared list and swapped out when one fails.

## Playback

| Mode | What it does | Auto picks it when |
|---|---|---|
| RealPlayer (Symbian) | writes a one-line `.ram` file and opens it, so RealPlayer streams the video | never (manual; hasn't played on a C6-00 yet) |
| Download, then play | saves to the memory card, then plays the file (with the phone's own player on Symbian) | JSR-75 is there |
| In-app player | MMAPI (JSR-135) inside the app | no file access, video supported |
| Phone's player/browser | `platformRequest` with the stream URL | nothing else works |

Quality: H.263 288p 3GP, MPEG-4 270p, MPEG-4 360p (default tier is MPEG-4), H.264 360p.

## Servers

Every start the app checks which servers this phone can reach (Menu > Servers shows the result and can
test again or reload the list, which also happens automatically every 3 days). Before a link goes to a
player the app opens it itself and reads the first bytes, moving to the next server if that fails. The list URL and a
personal YtAPILegacy server are in Settings, or in the JAD as `TubeME-List-URL` / `TubeME-Server`.

## Build and test

    ./build.sh        # needs ecj + tools/proguard.jar (ProGuard 7)
    ./test/run.sh     # desktop tests against test/mock.py
    test/emu/tour.sh dist/TubeME.jar shots   # MicroEmulator screenshots (run test/emu/setup-emu.sh once)

## License

GPL-3.0, see LICENSE.
