package tube;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.HttpConnection;

/**
 * Copies a video to a file, picking up where it stopped. The file is
 * written as name.part and renamed when complete, so a half file never
 * looks finished. When the connection drops or stalls, it asks the server
 * for the rest (an HTTP Range request) a few times before giving up; the
 * .part stays, and the next Save of the same video continues it.
 * No LCDUI here, so it runs in the desktop tests.
 */
final class Download {
    interface Watch {
        void say(String line);

        boolean cancelled();
    }

    /** No byte for this long counts as a dropped connection. */
    static long STALL = 30000;
    static int RETRIES = 5;
    static long RETRY_PAUSE = 2000;

    /**
     * Saves url to path. first is an already open connection to url (or
     * null). resumable: the server serves a fixed file (TubeME, yt2009),
     * not a conversion made on the fly. Returns the size of the finished file.
     */
    static long save(String url, HttpConnection first, boolean resumable, String path, Watch w) throws Exception {
        String part = path + ".part";
        long have = 0;
        if (resumable) {
            have = Files.size(part);
            if (have < 0) have = 0;
            if (have > 0) Log.add("dl found " + have + "b already saved");
        } else {
            Files.delete(part);
        }
        HttpConnection c = first;
        int tries = 0;
        long total = -1;
        boolean progressed = true;
        while (true) {
            if (w.cancelled()) {
                Net.close(c);
                return -1;
            }
            OutputStream out = null;
            InputStream in = null;
            Stall guard = null;
            try {
                if (have > 0 && (c == null || c == first)) {
                    // ask for the rest only
                    Net.close(c);
                    c = null;
                    w.say("Continuing from " + (have / 1024) + " KB");
                    c = Net.open(url, 60000, have);
                    if (c.getResponseCode() != HttpConnection.HTTP_PARTIAL) {
                        Log.add("dl server ignored the range: starting over");
                        have = 0;
                    }
                } else if (c == null) {
                    c = Net.open(url, 60000);
                }
                long len = c.getLength();
                if (len > 0) total = have + len;
                String type = c.getType();
                if (type != null && (type.startsWith("text/") || type.indexOf("json") >= 0)) {
                    throw new IOException("sent " + type + " instead of video");
                }
                Log.add("dl connected have=" + have + " len=" + len + " type=" + type + " -> " + part);
                if (have == 0) w.say(len > 0 ? "Downloading " + (len / 1024) + " KB" : "Downloading (size unknown)");
                out = have > 0 ? Files.openAppend(part) : Files.openOut(part);
                in = c.openInputStream();
                guard = new Stall(c);
                new Thread(guard).start();
                byte[] buf = new byte[4096];
                int n, step = total > 0 ? (int) (have * 100 / total) / 5 : (int) (have / 262144);
                long mark = have, logMark = have;
                progressed = false;
                while ((n = in.read(buf)) > 0) {
                    if (w.cancelled()) return -1;
                    guard.tick();
                    out.write(buf, 0, n);
                    have += n;
                    progressed = true;
                    if (have - logMark >= 262144) {
                        logMark = have;
                        Log.add("dl " + (have / 1024) + "K");
                    }
                    if (have - mark >= 32768) {
                        mark = have;
                        if (total > 0) {
                            int pct = (int) (have * 100 / total) / 5;
                            if (pct > step) {
                                step = pct;
                                w.say("Downloading " + (pct * 5) + "%");
                            }
                        } else {
                            int kb = (int) (have / 262144);
                            if (kb > step) {
                                step = kb;
                                w.say("Downloading " + (have / 1024) + " KB");
                            }
                        }
                    }
                }
                if (guard.fired) throw new IOException("no data for " + (STALL / 1000) + " s");
                // a dropped connection ends the read loop just like the end of the file does
                if (total > 0 && have < total) throw new IOException("connection dropped at " + (have / 1024) + " of " + (total / 1024) + " KB");
                if (have == 0) throw new Exception("Server sent an empty video");
                close(in, out, guard);
                in = null;
                out = null;
                Net.close(c);
                Files.replace(part, path);
                Log.add("dl done " + have + "b " + path);
                return have;
            } catch (Exception e) {
                Log.err("dl", e);
                close(in, out, guard);
                Net.close(c);
                c = null;
                if (have > 0 && e.getMessage() != null && e.getMessage().startsWith("HTTP 416")) {
                    // "nothing left to send": the saved part was already the whole file
                    Files.replace(part, path);
                    Log.add("dl was already complete " + have + "b");
                    return have;
                }
                boolean network = e instanceof IOException && !(e.getMessage() != null && e.getMessage().startsWith("sent "));
                // a non-resumable stream can't continue; a server that answers nothing new isn't worth asking again
                if (!resumable || !network || have == 0 || w.cancelled()) {
                    if (!resumable) Files.delete(part);
                    throw e;
                }
                if (progressed) tries = 0;
                if (++tries > RETRIES) {
                    throw new Exception("Download stopped at " + (have / 1024) + " KB (" + Api.msg(e)
                            + "). It is kept: Save again to continue");
                }
                w.say("Connection lost at " + (have / 1024) + " KB, continuing (" + tries + "/" + RETRIES + ")");
                try {
                    Thread.sleep(RETRY_PAUSE);
                } catch (InterruptedException ie) {
                }
            }
        }
    }

    private static void close(InputStream in, OutputStream out, Stall guard) {
        if (guard != null) guard.stop();
        try { if (in != null) in.close(); } catch (Throwable t) {}
        try { if (out != null) out.close(); } catch (Throwable t) {}
        Files.closeOut();
    }

    /** Closes the connection when no data has come for STALL ms, which ends a read that would block forever. */
    private static final class Stall implements Runnable {
        private final HttpConnection c;
        private volatile long last = System.currentTimeMillis();
        private volatile boolean stopped;
        volatile boolean fired;

        Stall(HttpConnection c) {
            this.c = c;
        }

        void tick() {
            last = System.currentTimeMillis();
        }

        void stop() {
            stopped = true;
        }

        public void run() {
            while (!stopped) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                if (!stopped && System.currentTimeMillis() - last > STALL) {
                    fired = true;
                    Log.add("dl stalled: closing");
                    Net.close(c);
                    return;
                }
            }
        }
    }
}
