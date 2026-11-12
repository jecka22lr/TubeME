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
            if (loc == null) throw new IOException("HTTP " + code);
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

    static void close(HttpConnection c) {
        try {
            if (c != null) c.close();
        } catch (Throwable t) {
        }
    }
}
