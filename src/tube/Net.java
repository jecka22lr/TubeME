package tube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/** HTTP GET helpers on top of the MIDP HttpConnection. */
final class Net {
    static final String UA = "TubeME/" + Api.VERSION + " (J2ME)";

    private Net() {}

    private static HttpConnection raw(String url) throws IOException {
        HttpConnection c;
        synchronized (Util.OPENING) {
            c = (HttpConnection) Connector.open(url, Connector.READ, true);
        }
        c.setRequestMethod(HttpConnection.GET);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    /** Default patience for metadata, thumbnails and ready files. */
    static final long TIMEOUT = 30000;

    static HttpConnection open(String url) throws IOException {
        return open(url, TIMEOUT);
    }

    /**
     * Opens url, following redirects by hand (not every phone does it), and
     * gives up when a server takes longer than timeoutMs to answer. Caller closes.
     */
    static HttpConnection open(String url, long timeoutMs) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            Job j = runJob(2, url, timeoutMs);
            HttpConnection c = j.c;
            int code = ((Integer) j.result).intValue();
            if (code >= 200 && code < 300) return c;
            String loc = (code >= 300 && code < 400) ? c.getHeaderField("Location") : null;
            close(c);
            if (loc == null) {
                Log.add("http " + code + " " + url);
                throw new IOException("HTTP " + code);
            }
            url = Util.resolve(url, loc);
        }
        throw new IOException("Too many redirects");
    }

    /** Body of url, at most max bytes. */
    static byte[] get(String url, int max) throws IOException {
        return get(url, max, TIMEOUT);
    }

    /**
     * Body of url, at most max bytes, all of it within timeoutMs. The limit
     * covers reading the body too: a server that sends headers and then
     * stalls would otherwise hang the caller forever.
     */
    static byte[] get(String url, int max, long timeoutMs) throws IOException {
        Job j = new Job(3, url);
        j.max = max;
        return (byte[]) runJob(j, timeoutMs).result;
    }

    /** The old untimed fetch; only ever run inside a Job. */
    private static byte[] fetch(String url, int max, Job j) throws IOException {
        HttpConnection c = null;
        for (int hop = 0; ; hop++) {
            if (hop >= 6) throw new IOException("Too many redirects");
            c = raw(url);
            j.c = c;
            if (j.abandoned) throw new IOException("abandoned");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) break;
            String loc = (code >= 300 && code < 400) ? c.getHeaderField("Location") : null;
            close(c);
            if (loc == null) {
                Log.add("http " + code + " " + url);
                throw new IOException("HTTP " + code);
            }
            url = Util.resolve(url, loc);
        }
        InputStream in = null;
        try {
            in = c.openInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > max) throw new IOException("Response too big");
            }
            return o.toByteArray();
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException e) {}
            }
            close(c);
        }
    }

    static String getText(String url) throws IOException {
        return getText(url, TIMEOUT);
    }

    static String getText(String url, long timeoutMs) throws IOException {
        byte[] d = get(url, 400000, timeoutMs);
        return Util.utf8(d, d.length);
    }

    /**
     * Asks url where it redirects to, without downloading the target.
     * yt2009 answers its conversion endpoints with a Location header.
     */
    static String redirect(String url, long timeoutMs) throws IOException {
        return (String) runJob(1, url, timeoutMs).result;
    }

    /**
     * Runs a request on a helper thread and gives up after timeoutMs. MIDP
     * has no socket timeouts, and a server that never answers would
     * otherwise hang the task forever with nothing on screen.
     * kind 0 = response code, 1 = redirect target, 2 = response code with
     * the connection left open in Job.c for the caller, 3 = the whole body.
     */
    private static Job runJob(int kind, String url, long timeoutMs) throws IOException {
        return runJob(new Job(kind, url), timeoutMs);
    }

    private static Job runJob(Job j, long timeoutMs) throws IOException {
        new Thread(j).start();
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (j) {
            while (!j.done) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    j.wait(left);
                } catch (InterruptedException e) {
                }
            }
            if (!j.done) {
                // the helper closes the connection itself if it ever gets one; closing it from
                // here can block until the stuck read gives up, so that happens on its own thread
                j.abandoned = true;
                final HttpConnection stuck = j.c;
                if (stuck != null) {
                    new Thread(new Runnable() {
                        public void run() {
                            close(stuck);
                        }
                    }).start();
                }
                throw new IOException("no answer after " + (timeoutMs / 1000) + " s");
            }
        }
        if (j.error != null) {
            if (j.error instanceof IOException) throw (IOException) j.error;
            throw new IOException(j.error.toString());
        }
        return j;
    }

    private static final class Job implements Runnable {
        private final int kind;
        private final String url;
        HttpConnection c;
        Object result;
        Throwable error;
        int max;
        volatile boolean done, abandoned;

        Job(int kind, String url) {
            this.kind = kind;
            this.url = url;
        }

        public void run() {
            try {
                if (kind == 3) {
                    result = fetch(url, max, this);
                } else {
                    c = raw(url);
                    result = kind == 1 ? (Object) redirectOf(c, url) : new Integer(c.getResponseCode());
                }
            } catch (Throwable t) {
                error = t;
            }
            synchronized (this) {
                done = true;
                // only a successful, still-wanted kind 2 hands its connection on
                // (kind 3 has closed its own already; closing again is harmless)
                if (kind != 2 || error != null || abandoned) close(c);
                notifyAll();
            }
        }
    }

    private static String redirectOf(HttpConnection c, String url) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null) throw new IOException("No video URL returned");
                return Util.resolve(url, loc);
            }
            if (code == 200) {
                // the phone followed the redirect on its own
                String u = c.getURL();
                return (u == null || u.length() == 0) ? url : u;
            }
            throw new IOException("HTTP " + code);
        } finally {
            close(c);
        }
    }

    /** Response code of url without following redirects or reading the body. Any answer means the server is up. */
    static int code(String url, long timeoutMs) throws IOException {
        return ((Integer) runJob(0, url, timeoutMs).result).intValue();
    }

    /**
     * Opens a video URL the way a player would and looks at the start of it.
     * Returns null when it looks servable, otherwise why not. Everything it
     * sees goes to the log, since players never say why they refuse a clip.
     */
    static String probe(String url) {
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = open(url);
            String type = c.getType();
            long len = c.getLength();
            in = c.openInputStream();
            byte[] b = new byte[64];
            int got = 0, n;
            while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
            String layout = mp4Layout(b, got);
            Log.add("probe type=" + type + " len=" + len + " ranges=" + c.getHeaderField("Accept-Ranges") + " " + layout);
            if (got < 12) return "server sent no video data";
            if (type != null && (type.startsWith("text/") || type.indexOf("json") >= 0)) return "server sent " + type + " instead of video";
            return null;
        } catch (Throwable t) {
            Log.err("probe", t);
            return Api.msg(t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            close(c);
        }
    }

    /**
     * Names the first two boxes of an MP4/3GP. "ftyp,moov" can be played
     * while it downloads; "ftyp,mdat" (index at the end) can only be played
     * from a complete file.
     */
    static String mp4Layout(byte[] b, int got) {
        if (got < 8) return "boxes=?";
        String first = box(b, 4);
        int size = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        String second = (size >= 8 && size + 8 <= got) ? box(b, size + 4) : "?";
        return "boxes=" + first + "," + second;
    }

    private static String box(byte[] b, int at) {
        StringBuffer s = new StringBuffer();
        for (int i = at; i < at + 4; i++) {
            int c = b[i] & 0xFF;
            s.append(c >= 32 && c < 127 ? (char) c : '?');
        }
        return s.toString();
    }

    static void close(HttpConnection c) {
        try {
            if (c != null) c.close();
        } catch (Throwable t) {
        }
    }
}
