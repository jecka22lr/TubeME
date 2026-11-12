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
        HttpConnection c = (HttpConnection) Connector.open(url, Connector.READ, true);
        c.setRequestMethod(HttpConnection.GET);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    /** Opens url, following redirects by hand (not every phone does it). Caller closes. */
    static HttpConnection open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpConnection c = raw(url);
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                close(c);
                throw e;
            }
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
        HttpConnection c = open(url);
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
        byte[] d = get(url, 400000);
        return Util.utf8(d, d.length);
    }

    /**
     * Asks url where it redirects to, without downloading the target.
     * yt2009 answers its conversion endpoints with a Location header.
     */
    static String redirect(String url) throws IOException {
        HttpConnection c = raw(url);
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
    static int code(String url) throws IOException {
        HttpConnection c = raw(url);
        try {
            return c.getResponseCode();
        } finally {
            close(c);
        }
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
