package tube;

import java.util.Hashtable;
import java.util.Vector;

/**
 * Talks to the same kinds of servers notPipe uses: YtAPILegacy (metadata +
 * video + conversion), yt2009 (video + conversion) and Invidious (metadata
 * fallback). Servers are picked at random and swapped out when they fail.
 */
final class Api {
    static final String VERSION = "0.1.11";

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

    /** H.264 256x144 MP4, small enough for phones that only decode H.264 up to QVGA. yt2009 only. */
    static final int Q_H264_144 = 4;
    /** H.263 176x144 + AMR in 3GP at 10 fps: the smallest files, for the weakest phones. yt2009 only. */
    static final int Q_H263_144 = 5;

    /** Indexed by quality id (ids are what settings store, so they never change). */
    static final String[] Q_NAMES = {
        "H.263 288p (3GP)", "MPEG-4 270p", "MPEG-4 360p", "H.264 360p", "H.264 144p", "H.263 144p (3GP)"
    };

    /**
     * Quality ids worth offering on this phone. H.264 360p is left out where
     * the phone is known not to show it (S60 3rd and 5th Edition decode
     * H.264 only up to QVGA).
     */
    static int[] qualities(String platform) {
        if (Util.h264OnlyQvga(platform)) return new int[] { Q_H263_144, Q_H264_144, Q_H263, Q_MP4V, Q_MP4V_360 };
        return new int[] { Q_H263_144, Q_H264_144, Q_H263, Q_MP4V, Q_MP4V_360, Q_H264 };
    }

    /** Thrown when no server that is up offers the chosen quality: the UI then asks for another. */
    static final class NoQuality extends Exception {
        NoQuality(String m) {
            super(m);
        }
    }

    /**
     * "server#quality" pairs a server answered "don't have that" for. Servers
     * run different versions, and asking is the only way to find out, so
     * what was learned is kept (and dropped when the server list is renewed).
     */
    final Hashtable missing = new Hashtable();

    private boolean offers(String kind, String base, int quality) {
        // YtAPILegacy never goes below 360p (it serves the 360p file for anything smaller)
        if (kind.equals("L")) return quality != Q_H264_144 && quality != Q_H263_144;
        return !missing.containsKey(base + "#" + quality);
    }

    /** How many video servers that aren't known to be down can serve this quality, as far as we know. */
    int serversFor(int quality) {
        Vector v = new Vector();
        add(v, "L", Util.cleanBase(custom));
        for (int i = 0; i < legacy.size(); i++) add(v, "L", (String) legacy.elementAt(i));
        for (int i = 0; i < yt2009.size(); i++) add(v, "Y", (String) yt2009.elementAt(i));
        int n = 0;
        for (int i = 0; i < v.size(); i++) {
            String[] e = (String[]) v.elementAt(i);
            if (!dead.containsKey(e[1]) && offers(e[0], e[1], quality)) n++;
        }
        return n;
    }

    /**
     * yt2009 addresses that produce each quality, best first. Servers differ
     * by version: a 404 on one address means "try the next", so a server
     * without /http_mpeg4_360 still serves its plain /http_mpeg4.
     */
    private static String[] yt2009Paths(int quality) {
        switch (quality) {
            case Q_H263: return new String[] { "/http_3gp_288?v=" };
            case Q_MP4V: return new String[] { "/http_mpeg4?v=" };
            case Q_MP4V_360: return new String[] { "/http_mpeg4_360?v=", "/http_mpeg4?v=" };
            case Q_H264_144: return new String[] { "/mp4_144?v=" };
            case Q_H263_144: return new String[] { "/http_3gp?v=" };
            default: return new String[] { "/channel_fh264_getvideo?v=" };
        }
    }

    /** How long a yt2009 server gets to fetch/convert a video before we move on. */
    static long PREPARE_TIMEOUT = 90000;
    /** YtAPILegacy converts the whole video before the first byte arrives. */
    static long CONVERT_TIMEOUT = 300000;
    /** A server that failed is not asked again for this long. */
    static long RETRY_DEAD_AFTER = 120000;
    static long PING_TIMEOUT = 8000;

    /** Gets one line per step worth showing the user. */
    interface Progress {
        void say(String line);
    }

    Progress progress;

    private void say(String line) {
        Progress p = progress;
        if (p != null) p.say(line);
    }

    static String host(String base) {
        int i = base.indexOf("://");
        String h = i < 0 ? base : base.substring(i + 3);
        int j = h.indexOf('/');
        return j < 0 ? h : h.substring(0, j);
    }

    Vector legacy = new Vector();
    Vector yt2009 = new Vector();
    Vector invidious = new Vector();
    /** Optional user-set YtAPILegacy server, always tried first. */
    String custom = "";

    /** Last YtAPILegacy server that answered. */
    private String lastGood;
    /** Servers seen answering / unreachable this session (base URL keys; dead maps to when). */
    private final Hashtable good = new Hashtable();
    private final Hashtable dead = new Hashtable();
    /** Reachable, but search/info from it failed: last for metadata, still fine for video. */
    private final Hashtable metaBad = new Hashtable();

    void markGood(String base) {
        good.put(base, "");
        dead.remove(base);
    }

    private void markDead(String base) {
        dead.put(base, new Long(System.currentTimeMillis()));
        good.remove(base);
    }

    /** Files a metadata failure: no connection means down, anything else means "answers, but not with this". */
    private void failed(String base, Exception ex) {
        String m = ex.getMessage();
        boolean network = ex instanceof java.io.IOException && (m == null || !m.startsWith("HTTP "));
        if (network) markDead(base);
        else metaBad.put(base, "");
    }

    /**
     * Is this server reachable? YtAPILegacy video links are built without
     * asking the server, so check before handing one to a player: a player
     * given a dead address fails in ways we never see.
     */
    private boolean alive(String base) {
        if (good.containsKey(base)) return true;
        Object when = dead.get(base);
        if (when != null && System.currentTimeMillis() - ((Long) when).longValue() < RETRY_DEAD_AFTER) return false;
        return ping(base);
    }

    /** Reply time of the last check per server, in ms. */
    final Hashtable ms = new Hashtable();

    /** Every known server as { kind, base }. */
    Vector servers() {
        Vector v = new Vector();
        add(v, "YtAPILegacy", Util.cleanBase(custom));
        for (int i = 0; i < legacy.size(); i++) add(v, "YtAPILegacy", (String) legacy.elementAt(i));
        for (int i = 0; i < yt2009.size(); i++) add(v, "yt2009", (String) yt2009.elementAt(i));
        for (int i = 0; i < invidious.size(); i++) add(v, "Invidious", (String) invidious.elementAt(i));
        return ranked(v, 0);
    }

    /** Checks one server from this phone, on this connection. Any HTTP answer counts as up. */
    boolean ping(String base) {
        long t = System.currentTimeMillis();
        try {
            int code = Net.code(base + "/health", PING_TIMEOUT);
            // 404 is fine (older servers have no /health); 5xx means it's up but broken
            if (code >= 500) throw new Exception("HTTP " + code);
            long took = System.currentTimeMillis() - t;
            ms.put(base, String.valueOf(took));
            markGood(base);
            Log.add("ping ok " + took + "ms http=" + code + " " + base);
            return true;
        } catch (Throwable e) {
            ms.remove(base);
            markDead(base);
            Log.err("ping " + base, e);
            return false;
        }
    }

    /**
     * Checks servers from this phone. Video servers (YtAPILegacy, yt2009) are
     * always all tested. Invidious lists run to dozens and only a few are
     * needed for search, so unless full is set testing stops once enough
     * of them answered. Returns how many answered.
     */
    int sweep(boolean full) {
        int up = 0, tested = 0, invUp = 0;
        Vector v = new Vector();
        add(v, "L", Util.cleanBase(custom));
        for (int i = 0; i < legacy.size(); i++) add(v, "L", (String) legacy.elementAt(i));
        for (int i = 0; i < yt2009.size(); i++) add(v, "Y", (String) yt2009.elementAt(i));
        addShuffled(v, invidious, "I");
        for (int i = 0; i < v.size(); i++) {
            String[] e = (String[]) v.elementAt(i);
            boolean inv = e[0].equals("I");
            if (inv && !full && invUp >= ENOUGH_INVIDIOUS) break;
            tested++;
            if (ping(e[1])) {
                up++;
                if (inv) invUp++;
            }
        }
        Log.add("sweep " + up + "/" + tested + " up, " + (v.size() - tested) + " not tested");
        return up;
    }

    static final int ENOUGH_INVIDIOUS = 5;

    /** "ok 320 ms", "down" or "not tested". */
    String status(String base) {
        if (dead.containsKey(base)) return "down";
        if (!good.containsKey(base)) return "not tested";
        Object t = ms.get(base);
        return t == null ? "ok" : "ok " + t + " ms";
    }

    /** Lower is better: measured reply time, then answered-but-untimed, then untested, then down. */
    private long score(String base) {
        if (dead.containsKey(base)) return Long.MAX_VALUE;
        if (!good.containsKey(base)) return 2000000;
        Object t = ms.get(base);
        if (t == null) return 1000000;
        try {
            return Long.parseLong((String) t);
        } catch (Exception e) {
            return 1000000;
        }
    }

    /**
     * Sorts { kind, base } entries from index from on: fastest reply first,
     * untested after the tested ones, servers that failed last. Stable, so
     * the random order survives among equals.
     */
    private Vector ranked(Vector in, int from) {
        Vector out = new Vector();
        for (int k = 0; k < in.size(); k++) {
            Object e = in.elementAt(k);
            if (k < from) {
                out.addElement(e);
                continue;
            }
            long sc = score(((String[]) e)[1]);
            int at = out.size();
            while (at > from && score(((String[]) out.elementAt(at - 1))[1]) > sc) at--;
            out.insertElementAt(e, at);
        }
        return out;
    }

    Api() {
        legacy.addElement(DEFAULT_LEGACY);
    }

    /** A playable address plus what it is. */
    static final class Stream {
        String url;
        String ext;
        String mime;
        String server;
        /** True when the link is a ready file that is cheap to peek at before playing. */
        boolean probe;
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
        order = null;
        // servers get updated too: ask again what they offer
        missing.clear();
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
        // YtAPILegacy before Invidious (it can also convert), each group fastest first
        Vector l = new Vector();
        add(l, "L", lastGood);
        addShuffled(l, legacy, "L");
        l = ranked(l, 0);
        Vector i = new Vector();
        addShuffled(i, invidious, "I");
        i = ranked(i, 0);
        // servers that are down or already failed at metadata go behind everything that works
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < l.size(); k++) {
                String[] e = (String[]) l.elementAt(k);
                if (metaTrouble(e[1]) == (pass == 1)) add(v, e[0], e[1]);
            }
            for (int k = 0; k < i.size(); k++) {
                String[] e = (String[]) i.elementAt(k);
                if (metaTrouble(e[1]) == (pass == 1)) add(v, e[0], e[1]);
            }
        }
        return v;
    }

    private boolean metaTrouble(String base) {
        return dead.containsKey(base) || metaBad.containsKey(base);
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
                markGood(e[1]);
                metaBad.remove(e[1]);
                return out;
            } catch (Exception ex) {
                Log.err("list " + e[1], ex);
                failed(e[1], ex);
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
                failed(e[1], ex);
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
            markGood(e[1]);
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
        boolean big = screenWidth >= 320;
        if (v.thumb.indexOf("/thumbnail/") >= 0) return v.thumb + "?quality=" + (big ? "medium" : "default");
        // Invidious: default.jpg is 120x90, mqdefault.jpg 320x180
        if (big && v.thumb.endsWith("/default.jpg")) return v.thumb.substring(0, v.thumb.length() - 11) + "mqdefault.jpg";
        return v.thumb;
    }

    private Vector streamOrder(Video v, int quality) {
        Vector out = new Vector();
        add(out, "L", Util.cleanBase(custom));
        add(out, "L", v.source);
        int pinned = out.size();
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
        out = ranked(out, pinned);
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
        // a quality question only if some server said "I don't have that" and
        // no server that is up failed for another reason
        boolean anyMissing = false, otherFailure = false;
        for (int t = 0; t < n; t++) {
            int idx = (skip + t) % n;
            String[] e = (String[]) order.elementAt(idx);
            if (!e[0].equals("D") && !offers(e[0], e[1], quality)) {
                last = new Exception(host(e[1]) + " doesn't offer this quality");
                anyMissing = true;
                continue;
            }
            try {
                Stream s = new Stream();
                s.server = Util.baseOf(e[1]);
                s.next = idx + 1;
                boolean threeGp = quality == Q_H263 || quality == Q_H263_144;
                s.ext = threeGp ? "3gp" : "mp4";
                s.mime = threeGp ? "video/3gpp" : "video/mp4";
                s.probe = !e[0].equals("L");
                if (e[0].equals("D")) {
                    s.url = e[1];
                } else if (e[0].equals("L")) {
                    if (!alive(e[1])) throw new Exception("server unreachable");
                    String u = e[1] + "/direct_url?video_id=" + v.id;
                    if (quality == Q_H263) u += "&codec=h263";
                    else if (quality == Q_H264) u += "&quality=360";
                    else u += "&codec=mpeg4";
                    // ignored by the server; helps players that sniff the extension
                    s.url = u + "&ext=." + s.ext;
                } else {
                    String[] paths = yt2009Paths(quality);
                    say("Asking " + host(e[1]) + " to get the video ready (up to " + (PREPARE_TIMEOUT / 1000) + " s)");
                    for (int pi = 0; pi < paths.length; pi++) {
                        Log.add("ask " + e[1] + paths[pi] + v.id);
                        try {
                            s.url = Net.redirect(e[1] + paths[pi] + v.id, PREPARE_TIMEOUT);
                            break;
                        } catch (java.io.IOException io) {
                            String m = io.getMessage();
                            boolean missing = m != null && m.startsWith("HTTP 404");
                            if (missing && pi + 1 < paths.length) continue;
                            if (missing) {
                                this.missing.put(e[1] + "#" + quality, "");
                                throw new Exception("doesn't offer this quality");
                            }
                            // the server keeps converting after we stop waiting, so a later try is quick
                            if (m != null && m.startsWith("no answer")) throw new Exception("still preparing the video; try again in a minute");
                            throw io;
                        }
                    }
                    if (s.url.indexOf("/auth") >= 0 && s.url.indexOf(".html") >= 0) throw new Exception("needs a sign-in");
                }
                return s;
            } catch (Exception ex) {
                Log.err("stream " + e[1], ex);
                say(host(e[1]) + ": " + msg(ex));
                last = ex;
                String m = ex.getMessage();
                boolean notOffered = m != null && m.indexOf("doesn't offer this quality") >= 0;
                if (notOffered) anyMissing = true;
                else if (!dead.containsKey(e[1])) otherFailure = true;
            }
        }
        if (anyMissing && !otherFailure) throw new NoQuality(Q_NAMES[quality] + " isn't available from any server that is up");
        throw new Exception("No server could provide the video: " + msg(last));
    }

    int streamServers() {
        return order == null ? 0 : order.size();
    }
}
