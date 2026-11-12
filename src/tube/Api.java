package tube;

import java.util.Hashtable;
import java.util.Vector;

/**
 * Talks to the same kinds of servers notPipe uses: YtAPILegacy (metadata +
 * video + conversion), yt2009 (video + conversion) and Invidious (metadata
 * fallback). Servers are picked at random and swapped out when they fail.
 */
final class Api {
    static final String VERSION = "0.1.1";

    static final String DEFAULT_LIST_URL = "http://144.31.189.129/notPipe.json";
    static final String DEFAULT_LEGACY = "http://45.132.96.44:2823";

    /** H.263 352x288 + AMR in 3GP. Plays on nearly anything with video. */
    static final int Q_H263 = 0;
    /** MPEG-4 Visual, 270p (yt2009) or 360p (YtAPILegacy), MP4. */
    static final int Q_MP4V = 1;
    /** MPEG-4 Visual 360p, MP4. */
    static final int Q_MP4V_360 = 2;
    /** H.264 360p MP4, no conversion. */
    static final int Q_H264 = 3;

    static final String[] Q_NAMES = {
        "H.263 288p (3GP)", "MPEG-4 270p", "MPEG-4 360p", "H.264 360p"
    };

    Vector legacy = new Vector();
    Vector yt2009 = new Vector();
    Vector invidious = new Vector();
    /** Optional user-set YtAPILegacy server, always tried first. */
    String custom = "";

    /** Last YtAPILegacy server that answered. */
    private String lastGood;

    Api() {
        legacy.addElement(DEFAULT_LEGACY);
    }

    /** A playable address plus what it is. */
    static final class Stream {
        String url;
        String ext;
        String mime;
        String server;
        /** Pass as skip to get the next server. */
        int next;
    }

    // ---- instance list ----

    /** Loads the shared instance list. Returns how many servers it now knows. */
    int updateInstances(String listUrl) throws Exception {
        Object o = Json.parse(Net.getText(listUrl));
        if (!(o instanceof Hashtable)) throw new Exception("Bad instance list");
        Vector l = clean(Json.arr(o, "ytapilegacy"));
        Vector y = clean(Json.arr(o, "yt2009"));
        Vector i = clean(Json.arr(o, "invidious"));
        if (l.size() + y.size() + i.size() == 0) throw new Exception("Instance list is empty");
        if (l.size() == 0) l.addElement(DEFAULT_LEGACY);
        legacy = l;
        yt2009 = y;
        invidious = i;
        return l.size() + y.size() + i.size();
    }

    private static Vector clean(Vector in) {
        Vector v = new Vector();
        for (int k = 0; k < in.size(); k++) {
            Object e = in.elementAt(k);
            if (e instanceof String) {
                String s = Util.cleanBase((String) e);
                if (s.length() > 0 && !v.contains(s)) v.addElement(s);
            }
        }
        return v;
    }

    /**
     * Random order, but plain http first: old phones often can't do modern
     * TLS, so https servers are a last resort.
     */
    private static void addShuffled(Vector out, Vector in, String tag) {
        Vector sh = Util.shuffled(in);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < sh.size(); k++) {
                String s = (String) sh.elementAt(k);
                boolean https = s.startsWith("https");
                if (https == (pass == 1)) add(out, tag, s);
            }
        }
    }

    private static void add(Vector out, String tag, String base) {
        if (base == null || base.length() == 0) return;
        for (int k = 0; k < out.size(); k++) {
            String[] e = (String[]) out.elementAt(k);
            if (e[1].equals(base)) return;
        }
        out.addElement(new String[] { tag, base });
    }

    private Vector metaOrder() {
        Vector v = new Vector();
        add(v, "L", Util.cleanBase(custom));
        add(v, "L", lastGood);
        addShuffled(v, legacy, "L");
        addShuffled(v, invidious, "I");
        return v;
    }

    // ---- metadata ----

    Vector search(String q) throws Exception {
        return list("/get_search_videos.php?query=" + Util.urlEncode(q),
                "/api/v1/search?type=video&fields=videoId,title,author,lengthSeconds,viewCount&q=" + Util.urlEncode(q));
    }

    Vector popular() throws Exception {
        return list("/get_top_videos.php",
                "/api/v1/popular?fields=videoId,title,author,lengthSeconds,viewCount");
    }

    private Vector list(String legacyPath, String invPath) throws Exception {
        Vector order = metaOrder();
        Exception last = null;
        for (int k = 0; k < order.size(); k++) {
            String[] e = (String[]) order.elementAt(k);
            try {
                boolean leg = e[0].equals("L");
                Object o = Json.parse(Net.getText(e[1] + (leg ? legacyPath : invPath)));
                if (!(o instanceof Vector)) throw new Exception(errorOf(o));
                Vector in = (Vector) o;
                Vector out = new Vector();
                for (int i = 0; i < in.size(); i++) {
                    Video v = leg ? fromLegacy(in.elementAt(i), e[1]) : fromInvidious(in.elementAt(i), e[1]);
                    if (v != null) out.addElement(v);
                }
                if (leg) lastGood = e[1];
                return out;
            } catch (Exception ex) {
                Log.err("list " + e[1], ex);
                last = ex;
                if (e[1].equals(lastGood)) lastGood = null;
            }
        }
        throw new Exception("No server answered" + (last == null ? "" : ": " + msg(last)));
    }

    Video info(String id) throws Exception {
        Vector order = metaOrder();
        Exception last = null;
        for (int k = 0; k < order.size(); k++) {
            String[] e = (String[]) order.elementAt(k);
            boolean leg = e[0].equals("L");
            Object o;
            try {
                o = Json.parse(Net.getText(e[1] + (leg ? "/get-ytvideo-info.php?video_id=" + id
                        : "/api/v1/videos/" + id + "?local=true&fields=videoId,title,author,lengthSeconds,viewCount,"
                        + "description,publishedText,formatStreams")));
                if (!(o instanceof Hashtable)) throw new Exception("Bad answer");
            } catch (Exception ex) {
                Log.err("info " + e[1], ex);
                last = ex;
                if (e[1].equals(lastGood)) lastGood = null;
                continue;
            }
            Video v = leg ? fromLegacy(o, e[1]) : fromInvidious(o, e[1]);
            if (v == null) v = new Video();
            v.id = id;
            // the info answer doesn't repeat the id, so the thumbnail address is built here
            v.thumb = leg ? e[1] + "/thumbnail/" + id : e[1] + "/vi/" + id + "/default.jpg";
            v.source = leg ? e[1] : null;
            if (v.title.length() == 0 && v.author.length() == 0) {
                String err = Json.str(o, "error");
                last = new Exception(err.length() > 0 ? err : "Video unavailable");
                continue;
            }
            if (leg) {
                lastGood = e[1];
                v.published = Json.str(o, "published_at");
            } else {
                v.published = Json.str(o, "publishedText");
                Vector fs = Json.arr(o, "formatStreams");
                if (fs.size() > 0) v.stream = Util.resolve(e[1], Json.str(fs.elementAt(0), "url"));
            }
            v.description = Json.str(o, "description");
            return v;
        }
        throw new Exception(last == null ? "No servers configured" : msg(last));
    }

    private static String errorOf(Object o) {
        String s = Json.str(o, "error");
        return s.length() > 0 ? s : "Bad answer";
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.toString() : m;
    }

    private static Video fromLegacy(Object o, String base) {
        if (!(o instanceof Hashtable)) return null;
        Video v = new Video();
        v.id = Json.str(o, "video_id");
        v.title = Json.str(o, "title");
        v.author = Json.str(o, "author");
        v.duration = niceDuration(Json.str(o, "duration"));
        v.views = Json.str(o, "views");
        v.thumb = base + "/thumbnail/" + v.id;
        v.source = base;
        return v;
    }

    private static Video fromInvidious(Object o, String base) {
        if (!(o instanceof Hashtable)) return null;
        String id = Json.str(o, "videoId");
        Video v = new Video();
        v.id = id;
        v.title = Json.str(o, "title");
        v.author = Json.str(o, "author");
        int len = Util.toInt(Json.str(o, "lengthSeconds"), -1);
        v.duration = len > 0 ? Util.duration(len) : "";
        v.views = Json.str(o, "viewCount");
        v.thumb = base + "/vi/" + id + "/default.jpg";
        return v;
    }

    /** "PT3M21S" -> "3:21"; anything else is passed through. */
    static String niceDuration(String d) {
        if (d.length() < 3 || d.charAt(0) != 'P') return d;
        int total = 0, cur = 0;
        for (int i = 1; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') cur = cur * 10 + (c - '0');
            else {
                if (c == 'D') total += cur * 86400;
                else if (c == 'H') total += cur * 3600;
                else if (c == 'M') total += cur * 60;
                else if (c == 'S') total += cur;
                cur = 0;
            }
        }
        return Util.duration(total);
    }

    // ---- video ----

    /** Thumbnail URL sized for the screen (YtAPILegacy scales server-side). */
    static String thumbUrl(Video v, int screenWidth) {
        if (v.thumb.length() == 0) return "";
        if (v.thumb.indexOf("/thumbnail/") >= 0) return v.thumb + "?quality=" + (screenWidth >= 320 ? "medium" : "default");
        return v.thumb;
    }

    private Vector streamOrder(Video v, int quality) {
        Vector out = new Vector();
        add(out, "L", Util.cleanBase(custom));
        add(out, "L", v.source);
        add(out, "L", lastGood);
        Vector mix = new Vector();
        addShuffled(mix, legacy, "L");
        addShuffled(mix, yt2009, "Y");
        Vector sh = Util.shuffled(mix);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < sh.size(); k++) {
                String[] e = (String[]) sh.elementAt(k);
                if (e[1].startsWith("https") == (pass == 1)) add(out, e[0], e[1]);
            }
        }
        if (quality == Q_H264 && v.stream != null && v.stream.length() > 0) {
            out.addElement(new String[] { "D", v.stream });
        }
        return out;
    }

    /** Remembered so "other server" walks one stable list per video. */
    private Vector order;
    private String orderKey;

    /**
     * Finds a stream for v at the given quality, starting at server number
     * skip (wraps around). Throws when nobody can serve it.
     */
    Stream stream(Video v, int quality, int skip) throws Exception {
        String key = v.id + "/" + quality;
        if (order == null || !key.equals(orderKey)) {
            order = streamOrder(v, quality);
            orderKey = key;
        }
        int n = order.size();
        if (n == 0) throw new Exception("No video servers configured");
        Exception last = null;
        for (int t = 0; t < n; t++) {
            int idx = (skip + t) % n;
            String[] e = (String[]) order.elementAt(idx);
            try {
                Stream s = new Stream();
                s.server = Util.baseOf(e[1]);
                s.next = idx + 1;
                boolean threeGp = quality == Q_H263;
                s.ext = threeGp ? "3gp" : "mp4";
                s.mime = threeGp ? "video/3gpp" : "video/mp4";
                if (e[0].equals("D")) {
                    s.url = e[1];
                } else if (e[0].equals("L")) {
                    String u = e[1] + "/direct_url?video_id=" + v.id;
                    if (quality == Q_H263) u += "&codec=h263";
                    else if (quality == Q_H264) u += "&quality=360";
                    else u += "&codec=mpeg4";
                    // ignored by the server; helps players that sniff the extension
                    s.url = u + "&ext=." + s.ext;
                } else {
                    String path;
                    if (quality == Q_H263) path = "/http_3gp_288?v=";
                    else if (quality == Q_MP4V) path = "/http_mpeg4?v=";
                    else if (quality == Q_MP4V_360) path = "/http_mpeg4_360?v=";
                    else path = "/channel_fh264_getvideo?v=";
                    s.url = Net.redirect(e[1] + path + v.id);
                }
                return s;
            } catch (Exception ex) {
                Log.err("stream " + e[1], ex);
                last = ex;
            }
        }
        throw new Exception("No server could provide the video: " + msg(last));
    }

    int streamServers() {
        return order == null ? 0 : order.size();
    }
}
