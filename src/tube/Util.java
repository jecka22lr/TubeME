package tube;

import java.util.Random;
import java.util.Vector;

/** String and URL helpers that CLDC doesn't give us. */
final class Util {
    static final Random RND = new Random();

    private Util() {}

    /** Percent-encodes as UTF-8. */
    static String urlEncode(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append((char) c);
            } else if (c < 0x80) {
                hex(b, c);
            } else if (c < 0x800) {
                hex(b, 0xC0 | (c >> 6));
                hex(b, 0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length()) {
                int lo = s.charAt(++i);
                int cp = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                hex(b, 0xF0 | (cp >> 18));
                hex(b, 0x80 | ((cp >> 12) & 0x3F));
                hex(b, 0x80 | ((cp >> 6) & 0x3F));
                hex(b, 0x80 | (cp & 0x3F));
            } else {
                hex(b, 0xE0 | (c >> 12));
                hex(b, 0x80 | ((c >> 6) & 0x3F));
                hex(b, 0x80 | (c & 0x3F));
            }
        }
        return b.toString();
    }

    private static void hex(StringBuffer b, int v) {
        b.append('%');
        b.append("0123456789ABCDEF".charAt((v >> 4) & 15));
        b.append("0123456789ABCDEF".charAt(v & 15));
    }

    /** UTF-8 decode that never throws; not every phone knows the "UTF-8" name. */
    static String utf8(byte[] d, int len) {
        StringBuffer b = new StringBuffer(len);
        int i = 0;
        while (i < len) {
            int c = d[i++] & 0xFF;
            if (c < 0x80) {
                b.append((char) c);
            } else if (c >= 0xC0 && c < 0xE0 && i < len) {
                b.append((char) (((c & 0x1F) << 6) | (d[i++] & 0x3F)));
            } else if (c >= 0xE0 && c < 0xF0 && i + 1 < len) {
                b.append((char) (((c & 0x0F) << 12) | ((d[i] & 0x3F) << 6) | (d[i + 1] & 0x3F)));
                i += 2;
            } else if (c >= 0xF0 && i + 2 < len) {
                // outside the BMP (emoji): old phone fonts can't draw these anyway
                i += 3;
            }
        }
        return b.toString();
    }

    /** "http://host:port" part of a URL (no trailing slash). */
    static String baseOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) return url;
        int j = url.indexOf('/', i + 3);
        return j < 0 ? url : url.substring(0, j);
    }

    /** Resolves a maybe-relative URL from a server against that server. */
    static String resolve(String base, String url) {
        if (url == null || url.length() == 0) return "";
        if (url.startsWith("//")) return "http:" + url;
        if (url.startsWith("/")) return baseOf(base) + url;
        if (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8)) return url;
        return baseOf(base) + "/" + url;
    }

    /** Trims and drops a trailing slash so base + "/path" always works. */
    static String cleanBase(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.length() > 0 && s.indexOf("://") < 0) s = "http://" + s;
        return s;
    }

    static String duration(int sec) {
        if (sec < 0) return "";
        int h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        StringBuffer b = new StringBuffer();
        if (h > 0) {
            b.append(h).append(':');
            if (m < 10) b.append('0');
        }
        b.append(m).append(':');
        if (s < 10) b.append('0');
        b.append(s);
        return b.toString();
    }

    static int toInt(String s, int def) {
        try {
            int dot = s.indexOf('.');
            return Integer.parseInt(dot < 0 ? s : s.substring(0, dot));
        } catch (Exception e) {
            return def;
        }
    }

    /** Pulls an 11-char video id out of a URL or returns the input if it already is one. */
    static String videoId(String in) {
        in = in.trim();
        String[] marks = { "v=", "youtu.be/", "/shorts/", "/embed/", "/v/" };
        for (int i = 0; i < marks.length; i++) {
            int k = in.indexOf(marks[i]);
            if (k >= 0) {
                String id = idAt(in, k + marks[i].length());
                if (id != null) return id;
            }
        }
        String id = idAt(in, 0);
        return (id != null && in.length() == 11) ? id : null;
    }

    private static String idAt(String s, int from) {
        int n = 0;
        while (from + n < s.length() && n < 11) {
            char c = s.charAt(from + n);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') n++;
            else break;
        }
        return n == 11 ? s.substring(from, from + 11) : null;
    }

    /** Fisher-Yates on a copy. */
    static Vector shuffled(Vector in) {
        Vector v = new Vector();
        for (int i = 0; i < in.size(); i++) v.addElement(in.elementAt(i));
        for (int i = v.size() - 1; i > 0; i--) {
            int j = (RND.nextInt() >>> 1) % (i + 1);
            Object t = v.elementAt(i);
            v.setElementAt(v.elementAt(j), i);
            v.setElementAt(t, j);
        }
        return v;
    }

    static String replace(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return s;
        StringBuffer sb = new StringBuffer();
        int from = 0;
        while (i >= 0) {
            sb.append(s.substring(from, i)).append(b);
            from = i + a.length();
            i = s.indexOf(a, from);
        }
        sb.append(s.substring(from));
        return sb.toString();
    }

    /** S60 platform version out of microedition.platform ("5.0", "3.2"...), or null. */
    static String s60Version(String platform) {
        if (platform == null) return null;
        String key = "sw_platform_version=";
        int i = platform.indexOf(key);
        if (i < 0) return null;
        int from = i + key.length(), to = from;
        while (to < platform.length() && platform.charAt(to) != ';') to++;
        return platform.substring(from, to).trim();
    }

    /**
     * S60 3rd Edition and 5th Edition phones only decode H.264 up to about
     * QVGA (Nokia's own 5800 sheet: "MPEG4-AVC playback 30fps QVGA", while
     * MPEG-4 SP goes to VGA/nHD). Symbian^3 and later report 5.2 and up.
     */
    static boolean h264OnlyQvga(String platform) {
        String v = s60Version(platform);
        return v != null && (v.startsWith("3.") || v.equals("5.0") || v.equals("5.1"));
    }

    /** JSR-75 present? Lives here so asking doesn't load the Files class. */
    static boolean hasFiles() {
        return System.getProperty("microedition.io.file.FileConnection.version") != null;
    }

    /** True on S60 / Symbian Java runtimes (RealPlayer is there to do the heavy lifting). */
    static boolean isSymbian() {
        String p = System.getProperty("microedition.platform");
        if (p != null) {
            String l = p.toLowerCase();
            if (l.indexOf("platform=s60") >= 0 || l.indexOf("series60") >= 0 || l.indexOf("symbian") >= 0) return true;
        }
        if (System.getProperty("com.symbian.midp.serversocket.support") != null) return true;
        if (System.getProperty("com.symbian.default.to.suite.icon") != null) return true;
        String[] cls = { "com.symbian.gcf.NativeInputStream", "com.symbian.midp.io.protocol.http.Protocol",
                "com.nokia.mj.impl.rt.support.Jvm" };
        for (int i = 0; i < cls.length; i++) {
            try {
                Class.forName(cls[i]);
                return true;
            } catch (Throwable t) {
            }
        }
        return false;
    }
}
