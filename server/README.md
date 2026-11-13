# TubeME server

A small server you run on your own PC. It fetches a YouTube video, converts it to something an old
phone can play, and hands it to TubeME. It also answers search, video details and thumbnails, so
TubeME works with it alone.

What it adds over the public servers:

- **240p** (MPEG-4 or H.264 inside 320x240), the natural size for QVGA phones
- **144p** in H.264 (256x144) and H.263 3GP (176x144)
- it tells the app exactly which qualities it makes, and reports conversion progress in percent
- picture sizes are always multiples of 16, which some phone decoders insist on

## Setup (Windows)

1. Install Python 3 from python.org. Tick "Add python.exe to PATH" in the installer.
2. Install ffmpeg: `winget install Gyan.FFmpeg`, then open a new terminal so it is on the PATH.
3. Install yt-dlp: `pip install yt-dlp`
4. Run it: `python tubeme_server.py`
5. Windows Firewall will ask: allow it on **private** networks.
6. It prints a line like `in TubeME, set My TubeME server to http://192.168.1.20:8765`.
   Type that address into TubeME: Settings > My TubeME server.

The phone has to be on the same Wi-Fi as the PC, and the PC has to be on with the server running.

To check the phone can reach the PC before involving YouTube, run `python tubeme_server.py --demo`:
it serves generated test videos and needs no internet.

## Options

    --port 8765            port to listen on
    --cache <folder>       where downloads and conversions are kept (default: cache/ next to the script)
    --max-cache-mb 2000    oldest files are deleted past this size
    --workers 2            conversions running at once
    --demo                 test videos only, no YouTube

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
| h264-360 | YouTube's own 360p file, not converted | MP4 |

3GP files get AMR sound when ffmpeg has the AMR encoder (`libopencore_amrnb`) and AAC otherwise. The
server says which at startup. AAC is fine on Symbian; very old phones may play AAC 3GP files silent.

## Things to know

- There is no password. Run it on your home network; don't forward the port to the internet.
- yt-dlp needs updating now and then when YouTube changes something: `pip install -U yt-dlp`
- `python test_server.py` checks the server end to end in demo mode.
