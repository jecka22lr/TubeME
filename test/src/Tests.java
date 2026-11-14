package tube;
import java.util.*;
import javax.microedition.io.HttpConnection;
/** Desktop checks of everything that isn't LCDUI, against test/mock.py. */
public class Tests {
    static int n;
    static void ok(boolean c, String what) { n++; if (!c) throw new RuntimeException("FAILED: " + what); System.out.println("ok " + what); }
    static void eq(Object a, Object b, String what) { ok(a == null ? b == null : a.equals(b), what + " (got " + a + ", want " + b + ")"); }
    static final String L = "http://127.0.0.1:18081", DEAD = "http://127.0.0.1:18082", Y = "http://127.0.0.1:18083",
            YBAD = "http://127.0.0.1:18084", INV = "http://127.0.0.1:18085";

    public static void main(String[] a) {
        try { run(); System.out.println("TESTS PASSED (" + n + " checks)"); System.exit(0); }
        catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }

    static void run() throws Exception {
        // --- Json ---
        Object o = Json.parse(" {\"a\":\"x\\n\\\"q\\\" \\u00e9\\/\",\"n\":-1.5e3,\"b\":true,\"z\":null,\"l\":[1,[2,{}],\"s\"],\"e\":{}} ");
        eq(Json.str(o, "a"), "x\n\"q\" \u00e9/", "json escapes");
        eq(Json.str(o, "n"), "-1.5e3", "json number");
        eq(Json.str(o, "b"), "true", "json bool");
        eq(Json.str(o, "z"), "", "json null");
        eq(Json.str(o, "missing"), "", "json missing");
        eq(Integer.valueOf(Json.arr(o, "l").size()), Integer.valueOf(3), "json array");
        boolean threw = false; try { Json.parse("{\"a\":"); } catch (Exception e) { threw = true; } ok(threw, "json truncated throws");
        threw = false; try { Json.parse("<html>502</html>"); } catch (Exception e) { threw = true; } ok(threw, "json html throws");

        // --- Util ---
        eq(Util.urlEncode("gatos & c\u00e3es \u20ac\uD83D\uDE00"), "gatos%20%26%20c%C3%A3es%20%E2%82%AC%F0%9F%98%80", "urlEncode utf8");
        byte[] u = "a\u00e9\u20ac\uD83D\uDE00b".getBytes("UTF-8");
        eq(Util.utf8(u, u.length), "a\u00e9\u20acb", "utf8 decode drops astral");
        eq(Util.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"), "dQw4w9WgXcQ", "id from watch url");
        eq(Util.videoId("youtu.be/dQw4w9WgXcQ"), "dQw4w9WgXcQ", "id from short url");
        eq(Util.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ?x"), "dQw4w9WgXcQ", "id from shorts");
        eq(Util.videoId(" dQw4w9WgXcQ "), "dQw4w9WgXcQ", "bare id");
        eq(Util.videoId("hello"), null, "not an id");
        eq(Util.resolve("http://h:1/a/b?c", "/x"), "http://h:1/x", "resolve relative");
        eq(Util.resolve("http://h:1/a", "HTTP://o/x"), "HTTP://o/x", "resolve absolute");
        eq(Util.cleanBase(" host:80/ "), "http://host:80", "cleanBase");
        eq(Util.duration(3725), "1:02:05", "duration h");
        eq(Util.duration(61), "1:01", "duration m");
        eq(Api.niceDuration("PT16M21S"), "16:21", "iso duration");
        eq(Api.niceDuration("3:21"), "3:21", "plain duration");

        String c6 = "NokiaC6-00/40.0.021/sw_platform=S60;sw_platform_version=5.0;java_build_version=1.4.48";
        eq(Util.s60Version(c6), "5.0", "s60 version");
        ok(Util.h264OnlyQvga(c6), "S60 5th: H.264 only QVGA");
        ok(Util.h264OnlyQvga("NokiaN82/31.0.016/sw_platform=S60;sw_platform_version=3.1"), "S60 3.1: H.264 only QVGA");
        ok(!Util.h264OnlyQvga("NokiaN8-00/111.030.0609/sw_platform=S60;sw_platform_version=5.3;java_build_version=2.2.54"), "Symbian^3+: no limit note");
        ok(!Util.h264OnlyQvga("SonyEricssonK800i/R1KG001"), "non-Symbian: no note");
        ok(!Util.h264OnlyQvga(null), "no platform: no note");

        int[] qc6 = Api.qualities(c6);
        eq(Integer.valueOf(qc6.length), Integer.valueOf(7), "S60 5th quality count");
        ok(qc6[2] == Api.Q_MP4V_240 && qc6[3] == Api.Q_H264_240, "240p offered on S60 5th");
        for (int i = 0; i < qc6.length; i++) ok(qc6[i] != Api.Q_H264, "H.264 360p hidden on S60 5th");
        ok(qc6[1] == Api.Q_H264_144, "H.264 144p still offered on S60 5th");
        eq(Integer.valueOf(Api.qualities("NokiaN8-00/x/sw_platform=S60;sw_platform_version=5.2").length), Integer.valueOf(8), "all offered on Symbian^3");
        eq(Integer.valueOf(Api.qualities(null).length), Integer.valueOf(8), "all offered when platform unknown");
        eq(Integer.valueOf(Api.Q_NAMES.length), Integer.valueOf(8), "a name per quality id");
        eq(Integer.valueOf(Api.Q_KEYS.length), Integer.valueOf(8), "a server key per quality id");
        eq(Api.Q_KEYS[Api.Q_MP4V_240], "mp4v-240", "240p key"); eq(Api.Q_KEYS[Api.Q_H263_144], "h263-144", "144p key");

        eq(Log.symbian("-191"), " (host unreachable)", "symbian error explained");
        eq(Log.symbian("-1"), " (not found: often no free connection left, or no access point)", "symbian -1 explained");
        eq(Log.symbian("HTTP 404"), "", "other messages untouched"); eq(Log.symbian(null), "", "null message");
        eq(Log.symbian("-99999"), "", "unknown code left alone");

        // --- instance list ---
        Api api = new Api();
        eq(Integer.valueOf(api.updateInstances(L + "/notPipe.json")), Integer.valueOf(6), "instance list count");
        eq(Integer.valueOf(api.piped.size()), Integer.valueOf(1), "piped instances read");
        eq(Integer.valueOf(api.legacy.size()), Integer.valueOf(2), "legacy instances");
        eq(api.invidious.elementAt(0), INV, "trailing slash trimmed");

        // --- metadata with a dead server in the pool (run several times: order is random) ---
        for (int i = 0; i < 6; i++) {
            Api a2 = new Api(); a2.legacy = api.legacy; a2.invidious = api.invidious;
            Vector r = a2.search("gatos & c\u00e3es");
            eq(Integer.valueOf(r.size()), Integer.valueOf(3), "search failover run " + i);
            Video v = (Video) r.elementAt(1);
            eq(v.id, "abcDEF_-001", "search id");
            eq(v.title, "T\u00e9st \"vid\u00e9o\" \u21161 ", "search title utf8");
            eq(v.line(), "T\u00e9st \"vid\u00e9o\" \u21161  [3:21] - Canal \u00c7", "result line");
            eq(v.source, L, "source server");
        }
        Vector top = api.popular();
        eq(((Video) top.elementAt(0)).duration, "1:02:03", "popular iso duration");
        Video v = api.info("abcDEF_-001");
        eq(v.title, "Info title", "info title"); eq(v.duration, "16:21", "info duration");
        eq(v.description, "line1\nline2 \u00e9", "info description"); eq(v.views, "4242", "info views");
        eq(Api.thumbUrl(v, 240), L + "/thumbnail/abcDEF_-001?quality=default", "thumb small");
        eq(Api.thumbUrl(v, 360), L + "/thumbnail/abcDEF_-001?quality=medium", "thumb medium");
        Video it = new Video(); it.thumb = INV + "/vi/abcDEF_-001/default.jpg";
        eq(Api.thumbUrl(it, 360), INV + "/vi/abcDEF_-001/mqdefault.jpg", "invidious thumb bigger on wide screens");
        eq(Api.thumbUrl(it, 240), INV + "/vi/abcDEF_-001/default.jpg", "invidious thumb small on small screens");
        ok(Net.get(Api.thumbUrl(v, 240), 80000)[1] == 'P', "thumb fetch");

        // --- invidious fallback when every YtAPILegacy server is down ---
        Api inv = new Api(); inv.legacy = new Vector(); inv.legacy.addElement(DEAD); inv.invidious = api.invidious;
        Vector r = inv.search("x");
        eq(((Video) r.elementAt(0)).line(), "Inv result [1:02:05] - Inv author", "invidious search");
        Video iv = inv.info("abcDEF_-005");
        eq(iv.title, "Inv title", "invidious info"); eq(iv.stream, INV + "/latest_version?id=x&itag=18", "invidious stream url");
        eq(iv.duration, "1:01", "invidious duration");

        // --- unavailable video and no servers ---
        Api only = new Api(); only.legacy = new Vector(); only.legacy.addElement(L);
        threw = false; try { only.info("gone_gone_1"); } catch (Exception e) { threw = e.getMessage().indexOf("unavailable") >= 0; } ok(threw, "unavailable video reported");
        Api none = new Api(); none.legacy = new Vector(); none.legacy.addElement(DEAD);
        threw = false; try { none.search("x"); } catch (Exception e) { threw = e.getMessage().startsWith("No server answered"); } ok(threw, "all servers dead reported");

        // --- streams: YtAPILegacy ---
        String[] want = { "&codec=h263&ext=.3gp", "&codec=mpeg4&ext=.mp4", "&codec=mpeg4&ext=.mp4", "&quality=360&ext=.mp4" };
        for (int q = 0; q < 4; q++) {
            Api.Stream s = only.stream(v, q, 0);
            eq(s.url, L + "/direct_url?video_id=abcDEF_-001" + want[q], "legacy stream q" + q);
            eq(s.ext, q == 0 ? "3gp" : "mp4", "ext q" + q);
            byte[] d = Net.get(s.url, 200000);
            eq(Integer.valueOf(d.length), Integer.valueOf(76800), "legacy video bytes q" + q);
            ok((d[255] & 0xFF) == 255 && d[256] == 0, "video content q" + q);
        }

        // --- streams: yt2009 redirect, with a 404 server ahead of the good one ---
        String[] paths = { "-http_3gp_288", "-http_mpeg4", "-http_mpeg4_360", "-channel_fh264_getvideo" };
        for (int q = 0; q < 4; q++) {
            Api y = new Api(); y.legacy = new Vector(); y.yt2009.addElement(YBAD); y.yt2009.addElement(Y);
            Video nv = new Video(); nv.id = "abcDEF_-001";
            Api.Stream s = y.stream(nv, q, 0);
            eq(s.url, Y + "/assets/abcDEF_-001" + paths[q] + ".bin", "yt2009 stream q" + q);
            eq(s.server, Y, "yt2009 server q" + q);
        }
        eq(Integer.valueOf(Net.get(Y + "/http_mpeg4?v=abcDEF_-001", 200000).length), Integer.valueOf(76800), "manual redirect follow");

        // --- "other server" walks the list and wraps ---
        String GONE = "http://127.0.0.1:18099"; // nothing listens here: like a host the phone can't reach
        Api walk = new Api(); walk.legacy = new Vector(); walk.legacy.addElement(GONE); walk.legacy.addElement(L);
        walk.yt2009 = new Vector(); walk.yt2009.addElement(Y);
        Video wv = new Video(); wv.id = "abcDEF_-001";
        Hashtable seen = new Hashtable(); int skip = 0;
        for (int i = 0; i < 6; i++) { Api.Stream s = walk.stream(wv, Api.Q_MP4V, skip); seen.put(s.server, ""); skip = s.next; }
        eq(Integer.valueOf(walk.streamServers()), Integer.valueOf(3), "stream server count");
        eq(Integer.valueOf(seen.size()), Integer.valueOf(2), "other-server visits every live server");
        ok(!seen.containsKey(GONE), "unreachable YtAPILegacy server never handed to a player");
        Api goneOnly = new Api(); goneOnly.legacy = new Vector(); goneOnly.legacy.addElement(GONE);
        threw = false; try { goneOnly.stream(wv, Api.Q_H263, 0); } catch (Exception e) { threw = e.getMessage().indexOf("unreachable") >= 0; }
        ok(threw, "unreachable server reported instead of returned");
        Api.Stream d1 = inv.stream(iv, Api.Q_H264, 1);
        eq(d1.url, iv.stream, "invidious direct stream as last resort");

        // --- server test: sweep, status, pre-play probe ---
        Api sw = new Api(); sw.legacy = new Vector(); sw.legacy.addElement(GONE); sw.legacy.addElement(L);
        sw.yt2009.addElement(Y); sw.invidious.addElement(INV);
        eq(Integer.valueOf(sw.sweep(true)), Integer.valueOf(3), "sweep counts live servers");
        Api many = new Api(); many.legacy = new Vector(); many.legacy.addElement(L);
        for (int i = 0; i < 9; i++) many.invidious.addElement(INV + "/x" + i);
        eq(Integer.valueOf(many.sweep(false)), Integer.valueOf(1 + Api.ENOUGH_INVIDIOUS), "quick sweep stops after enough Invidious servers");
        eq(Integer.valueOf(many.sweep(true)), Integer.valueOf(10), "full sweep tests all");
        eq(sw.status(GONE), "down", "status down");
        ok(!sw.ping(DEAD), "a server answering 500 counts as down");
        ok(sw.status(L).startsWith("ok ") && sw.status(L).endsWith(" ms"), "status ok with time: " + sw.status(L));
        eq(new Api().status(L), "not tested", "status untested");
        eq(Integer.valueOf(sw.servers().size()), Integer.valueOf(4), "server list");
        Api.Stream ps = sw.stream(wv, Api.Q_MP4V, 0);
        ok(!ps.server.equals(GONE), "swept-dead server not first");
        eq(Net.probe(Y + "/http_mpeg4?v=abcDEF_-001"), null, "probe accepts a served file");
        ok(Net.probe(YBAD + "/assets/x.mp4") != null, "probe rejects a 404");
        ok(Net.probe(L + "/get_top_videos.php").indexOf("instead of video") > 0, "probe rejects json");
        byte[] fast = { 0,0,0,16, 'f','t','y','p', 'i','s','o','m', 0,0,0,0, 0,0,1,0, 'm','o','o','v' };
        eq(Net.mp4Layout(fast, fast.length), "boxes=ftyp,moov", "mp4 layout faststart");
        fast[20] = 'm'; fast[21] = 'd'; fast[22] = 'a'; fast[23] = 't';
        eq(Net.mp4Layout(fast, fast.length), "boxes=ftyp,mdat", "mp4 layout index at end");
        eq(Net.mp4Layout(fast, 4), "boxes=?", "mp4 layout short");

        // --- a server that never answers is given up on, with the steps reported ---
        final Vector said = new Vector();
        Api slow = new Api(); slow.legacy = new Vector(); slow.yt2009.addElement(Y);
        slow.progress = new Api.Progress() { public void say(String l) { said.addElement(l); } };
        long t1 = System.currentTimeMillis();
        threw = false; try { Net.redirect(Y + "/slow", 1000); } catch (java.io.IOException e) { threw = e.getMessage().equals("no answer after 1 s"); }
        ok(threw && System.currentTimeMillis() - t1 < 2500, "hung request times out");
        eq(Net.redirect(Y + "/slow", 8000), Y + "/assets/late.bin", "slow request inside the limit still works");
        slow.stream(wv, Api.Q_MP4V, 0);
        ok(((String) said.elementAt(0)).startsWith("Asking 127.0.0.1:18083 to get the video ready"), "progress line: " + said.elementAt(0));
        eq(Api.host("http://a.b:81/x"), "a.b:81", "host of url");

        threw = false; try { Net.open(Y + "/slow", 1000); } catch (java.io.IOException e) { threw = e.getMessage().equals("no answer after 1 s"); }
        ok(threw, "hung open times out");
        HttpConnection oc = Net.open(Y + "/slow", 8000);
        eq(Integer.valueOf(oc.getResponseCode()), Integer.valueOf(200), "slow open inside the limit works and follows the redirect");
        Net.close(oc);

        // --- a server that answers but fails at search is still used for video ---
        Api mb = new Api(); mb.legacy = new Vector(); mb.legacy.addElement(Y); mb.invidious.addElement(INV);
        eq(((Video) mb.search("x").elementAt(0)).title, "Inv result", "search falls through a server that 404s");
        eq(mb.stream(wv, Api.Q_H264, 0).url, Y + "/direct_url?video_id=abcDEF_-001&quality=360&ext=.mp4", "metadata failure does not block video");
        // --- an unreachable one is refused, then asked again once the wait is over ---
        Api rd = new Api(); rd.legacy = new Vector(); rd.legacy.addElement(GONE);
        threw = false; try { rd.stream(wv, Api.Q_H264, 0); } catch (Exception e) { threw = true; } ok(threw, "unreachable refused");
        ok(rd.status(GONE).equals("down"), "marked down");
        Api.RETRY_DEAD_AFTER = 0;
        rd.legacy.setElementAt(L, 0);
        Video rv2 = new Video(); rv2.id = "abcDEF_-002";
        ok(rd.stream(rv2, Api.Q_H264, 0).url.startsWith(L), "retried after the wait");
        Api.RETRY_DEAD_AFTER = 120000;

        // --- 144p and servers of different versions ---
        String OLD = "http://127.0.0.1:18086";
        Api q1 = new Api(); q1.legacy = new Vector(); q1.legacy.addElement(L); q1.yt2009.addElement(Y);
        Video qv = new Video(); qv.id = "abcDEF_-001";
        Api.Stream s144 = q1.stream(qv, Api.Q_H264_144, 0);
        eq(s144.url, Y + "/assets/abcDEF_-001-mp4_144.bin", "H.264 144p comes from yt2009, YtAPILegacy skipped");
        eq(s144.ext, "mp4", "144p mp4 ext");
        Video qv2 = new Video(); qv2.id = "abcDEF_-002";
        Api.Stream s3 = q1.stream(qv2, Api.Q_H263_144, 0);
        eq(s3.url, Y + "/assets/abcDEF_-002-http_3gp.bin", "H.263 144p address");
        eq(s3.ext, "3gp", "144p 3gp ext");
        Api lo = new Api(); lo.legacy = new Vector(); lo.legacy.addElement(L);
        threw = false; try { lo.stream(qv, Api.Q_H264_144, 0); } catch (Api.NoQuality e) { threw = e.getMessage().startsWith("H.264 144p isn't available"); }
        ok(threw, "YtAPILegacy alone can't do 144p: asks for another quality");
        eq(Integer.valueOf(lo.serversFor(Api.Q_H264_144)), Integer.valueOf(0), "no server count for 144p on YtAPILegacy");
        eq(Integer.valueOf(lo.serversFor(Api.Q_MP4V_360)), Integer.valueOf(1), "server count for 360p");
        eq(Integer.valueOf(q1.serversFor(Api.Q_H264_144)), Integer.valueOf(1), "144p counted on yt2009");
        Api old = new Api(); old.legacy = new Vector(); old.yt2009.addElement(OLD);
        eq(old.stream(qv, Api.Q_MP4V_360, 0).url, OLD + "/assets/abcDEF_-001-mpeg4.mp4", "360p falls back to /http_mpeg4 on a stock server");
        eq(Integer.valueOf(old.serversFor(Api.Q_H263)), Integer.valueOf(1), "unknown until asked");
        threw = false; try { old.stream(qv2, Api.Q_H263, 0); } catch (Api.NoQuality e) { threw = true; }
        ok(threw, "missing quality asks for another");
        eq(Integer.valueOf(old.serversFor(Api.Q_H263)), Integer.valueOf(0), "what the server lacks is remembered");
        ok(old.missing.containsKey(OLD + "#" + Api.Q_H263), "remembered per server and quality");
        eq(Integer.valueOf(old.serversFor(Api.Q_MP4V_360)), Integer.valueOf(1), "other qualities unaffected");
        Api mix = new Api(); mix.legacy = new Vector(); mix.legacy.addElement(L); mix.yt2009.addElement(OLD);
        ok(mix.stream(qv2, Api.Q_H263, 0).url.startsWith(L), "another server with the quality is used without asking");
        Api dn = new Api(); dn.legacy = new Vector(); dn.legacy.addElement(GONE);
        threw = false; try { dn.stream(qv, Api.Q_MP4V, 0); } catch (Api.NoQuality e) { threw = false; } catch (Exception e) { threw = true; }
        ok(threw, "servers being down is an error, not a quality question");
        eq(Settings.modeNames(true)[2], "RealPlayer, download first", "symbian mode name");
        int[] sm = Settings.modeIds(true), om = Settings.modeIds(false);
        eq(Integer.valueOf(sm.length), Integer.valueOf(5), "Symbian lists every mode");
        ok(sm[4] == Settings.MODE_EXTERNAL, "browser route offered on Symbian");
        for (int i = 0; i < om.length; i++) ok(om[i] != Settings.MODE_REAL, "RealPlayer not offered off Symbian");
        ok(sm[0] == Settings.MODE_AUTO && om[0] == Settings.MODE_AUTO, "Auto first on both");
        eq(Settings.modeNames(false)[2], "Download, then play", "other phones keep the plain name");
        Video qv3 = new Video(); qv3.id = "abcDEF_-003";
        threw = false; try { old.stream(qv3, Api.Q_H264_144, 0); } catch (Exception e) { threw = e.getMessage().indexOf("needs a sign-in") >= 0; }
        ok(threw, "sign-in redirect is not mistaken for a video");

        // --- a stuck server doesn't hold things up: the next is asked too, first answer wins ---
        String HANG = "http://127.0.0.1:18087";
        Api hg = new Api(); hg.legacy = new Vector(); hg.yt2009.addElement(HANG); hg.yt2009.addElement(Y);
        hg.markGood(HANG); hg.ms.put(HANG, "1"); hg.markGood(Y); hg.ms.put(Y, "900");
        final Vector hsaid = new Vector();
        hg.progress = new Api.Progress() { public void say(String l) { hsaid.addElement(l); } };
        Api.HEDGE_AFTER = 700;
        Video hv = new Video(); hv.id = "abcDEF_-004";
        long h0 = System.currentTimeMillis();
        Api.Stream hs = hg.stream(hv, Api.Q_H264_144, 0);
        long took = System.currentTimeMillis() - h0;
        eq(hs.server, Y, "second server wins while the first is stuck");
        ok(took >= 600 && took < 4000, "hedged after the head start, not after the full wait (" + took + " ms)");
        eq(hsaid.elementAt(0), "Asking 127.0.0.1:18087 to get the video ready (up to 90 s)", "first ask line");
        eq(hsaid.elementAt(1), "Also asking 127.0.0.1:18083 to get the video ready (up to 90 s)", "hedge line");
        // a ready file answers at once: only one server is asked
        Api one = new Api(); one.legacy = new Vector(); one.yt2009.addElement(Y); one.yt2009.addElement(HANG);
        one.markGood(Y); one.ms.put(Y, "1"); one.markGood(HANG); one.ms.put(HANG, "900");
        final Vector osaid = new Vector();
        one.progress = new Api.Progress() { public void say(String l) { osaid.addElement(l); } };
        one.stream(hv, Api.Q_H264_144, 0);
        eq(Integer.valueOf(osaid.size()), Integer.valueOf(1), "quick answer means one server asked");
        // everything stuck: gives up at the limit, says so, and asks that server last next time
        Api.PREPARE_TIMEOUT = 1500;
        Api stuck = new Api(); stuck.legacy = new Vector(); stuck.yt2009.addElement(HANG);
        threw = false; try { stuck.stream(hv, Api.Q_H264_144, 0); } catch (Api.NoQuality e) { threw = false; } catch (Exception e) { threw = e.getMessage().indexOf("no answer in 1 s") >= 0; }
        ok(threw, "all stuck: reported as no answer, not as a quality question");
        stuck.yt2009.addElement(Y); stuck.markGood(HANG); stuck.ms.put(HANG, "1"); stuck.markGood(Y); stuck.ms.put(Y, "900");
        Video hv2 = new Video(); hv2.id = "abcDEF_-005";
        Api.HEDGE_AFTER = 60000;
        eq(stuck.stream(hv2, Api.Q_H264_144, 0).server, Y, "a server that timed out is asked last for that quality");
        Api.PREPARE_TIMEOUT = 90000; Api.HEDGE_AFTER = 15000;

        // --- waits scale with the video ---
        eq(Integer.valueOf(Util.seconds("3:21")), Integer.valueOf(201), "m:ss");
        eq(Integer.valueOf(Util.seconds("1:02:05")), Integer.valueOf(3725), "h:mm:ss");
        eq(Integer.valueOf(Util.seconds("")), Integer.valueOf(-1), "no duration");
        eq(Integer.valueOf(Util.seconds("LIVE")), Integer.valueOf(-1), "not a duration");
        eq(Util.span(90000), "90 s", "span seconds"); eq(Util.span(250000), "4 min", "span minutes");
        Video tv = new Video();
        eq(Long.valueOf(Api.prepareTimeout(tv)), Long.valueOf(90000), "unknown length: base wait");
        tv.duration = "3:00"; eq(Long.valueOf(Api.prepareTimeout(tv)), Long.valueOf(120000), "3 min video: 2 min wait");
        tv.duration = "30:00"; eq(Long.valueOf(Api.prepareTimeout(tv)), Long.valueOf(390000), "30 min video: 6.5 min wait");
        tv.duration = "3:00:00"; eq(Long.valueOf(Api.prepareTimeout(tv)), Long.valueOf(900000), "capped at 15 min");
        tv.duration = "10:00"; eq(Long.valueOf(Api.convertTimeout(tv)), Long.valueOf(500000), "YtAPILegacy conversion wait");
        Api lg = new Api(); lg.legacy = new Vector(); lg.legacy.addElement(L); lg.yt2009.addElement(Y);
        lg.markGood(L); lg.ms.put(L, "1"); lg.markGood(Y); lg.ms.put(Y, "900");
        Video longv = new Video(); longv.id = "abcDEF_-006"; longv.duration = "55:00";
        eq(lg.stream(longv, Api.Q_MP4V_360, 0).server, Y, "55 min video: YtAPILegacy skipped for conversion");
        Video longv2 = new Video(); longv2.id = "abcDEF_-007"; longv2.duration = "55:00";
        ok(lg.stream(longv2, Api.Q_H264, 0).server.equals(L), "but still used for the unconverted file");
        Video shortv = new Video(); shortv.id = "abcDEF_-008"; shortv.duration = "54:59";
        ok(lg.stream(shortv, Api.Q_MP4V_360, 0).server.equals(L), "under the limit it converts");

        // --- the user's own TubeME server ---
        String OWN = "http://127.0.0.1:18088";
        Api.POLL_EVERY = 50;
        Api ow = new Api(); ow.legacy = new Vector(); ow.legacy.addElement(L); ow.yt2009.addElement(Y); ow.invidious.addElement(INV);
        ow.own = OWN + "/";
        final Vector wsaid = new Vector();
        ow.progress = new Api.Progress() { public void say(String l) { wsaid.addElement(l); } };
        Vector or = ow.search("x");
        Video o1 = (Video) or.elementAt(1);
        eq(o1.title, "Own r\u00e9sultat 1", "own server answers search first");
        eq(o1.duration, "1:06", "own duration"); eq(o1.views, "42", "own views");
        eq(((Video) or.elementAt(0)).views, "", "unknown view count hidden");
        Video oi = ow.info("abcDEF_-001");
        eq(oi.title, "Own title", "own info"); eq(oi.duration, "2:05", "own info duration");
        eq(oi.published, "2026-01-02", "own published"); eq(oi.description, "own description", "own description");
        eq(Api.thumbUrl(oi, 360), OWN + "/thumb?id=abcDEF_-001&w=320", "own thumb sized for the screen");
        eq(Api.thumbUrl(oi, 176), OWN + "/thumb?id=abcDEF_-001&w=120", "own thumb small");
        ok(Net.get(Api.thumbUrl(oi, 360), 80000)[1] == 'P', "own thumb fetch");
        eq(((Video) ow.popular().elementAt(0)).title, "Own popular", "popular from your own server");
        Api oldOwn = new Api(); oldOwn.legacy = new Vector(); oldOwn.legacy.addElement(L); oldOwn.own = Y;
        eq(((Video) oldOwn.popular().elementAt(0)).title, "Top one", "a server without /popular is skipped for popular");
        ok(oldOwn.status(Y).equals("not tested") || oldOwn.status(Y).startsWith("ok"), "and isn't marked down for it: " + oldOwn.status(Y));
        ok(ow.ping(OWN), "own server pings");
        eq(Integer.valueOf(ow.serversFor(Api.Q_MP4V_240)), Integer.valueOf(1), "240p: only the own server");
        eq(Integer.valueOf(ow.serversFor(Api.Q_H263)), Integer.valueOf(2), "288p: own server doesn't make it, the other two might");
        eq(Integer.valueOf(ow.serversFor(Api.Q_H264_144)), Integer.valueOf(2), "144p: own server and yt2009");
        eq(wsaid.elementAt(0), "Asking your server", "search says it asks your server");
        wsaid.removeAllElements();
        Api.Stream os = ow.stream(oi, Api.Q_MP4V_240, 0);
        eq(os.url, OWN + "/file/abcDEF_-001-mp4v-240.mp4", "own server converts and hands over the file");
        eq(os.server, OWN, "own stream server"); ok(os.probe, "own file is checked before playing");
        eq(wsaid.elementAt(0), "Your server is fetching the video", "progress: fetching");
        eq(wsaid.elementAt(1), "Converting 40%", "progress: 40%"); eq(wsaid.elementAt(2), "Converting 85%", "progress: 85%");
        eq(Integer.valueOf(Net.get(os.url, 200000).length), Integer.valueOf(76800), "own file downloads");
        Video oi2 = new Video(); oi2.id = "abcDEF_-002";
        String lackSrv = ow.stream(oi2, Api.Q_H263, 0).server;
        ok(!lackSrv.equals(OWN) && (lackSrv.equals(L) || lackSrv.equals(Y)), "a quality the own server lacks goes to the others (" + lackSrv + ")");
        Api only1 = new Api(); only1.legacy = new Vector(); only1.own = OWN;
        threw = false; try { only1.stream(oi2, Api.Q_MP4V, 0); } catch (Api.NoQuality e) { threw = true; }
        ok(threw, "own server alone: missing quality asks for another");
        Video bad = new Video(); bad.id = "brokenbroke";
        threw = false; try { only1.stream(bad, Api.Q_MP4V_240, 0); } catch (Api.NoQuality e) { threw = false; } catch (Exception e) { threw = e.getMessage().indexOf("Video unavailable") >= 0; }
        ok(threw, "the own server's error message reaches the user");
        Api notOwn = new Api(); notOwn.legacy = new Vector(); notOwn.legacy.addElement(L); notOwn.own = Y;
        ok(!notOwn.ping(Y), "an address that isn't a TubeME server is reported down");
        eq(((Video) notOwn.search("gatos & c\u00e3es").elementAt(0)).source, L, "and search carries on with the other servers");
        Api gone2 = new Api(); gone2.legacy = new Vector(); gone2.legacy.addElement(L); gone2.own = GONE;
        ok(gone2.stream(oi2, Api.Q_MP4V, 0).server.equals(L), "own server switched off: the others still serve");
        Api.POLL_EVERY = 1500;

        // --- comments from each kind of server ---
        Api cOwn = new Api(); cOwn.legacy = new Vector(); cOwn.legacy.addElement(L); cOwn.own = OWN;
        Vector cm = cOwn.comments("abcDEF_-001");
        eq(Integer.valueOf(cm.size()), Integer.valueOf(2), "own comments, empty ones dropped");
        String[] c0 = (String[]) cm.elementAt(0);
        eq(c0[0], "Zed", "comment author"); eq(Integer.valueOf(c0[1].length()), Integer.valueOf(503), "long comment trimmed");
        eq(c0[2], "12 likes, 2 days ago", "likes and age"); eq(((String[]) cm.elementAt(1))[2], "1 like", "single like, no age");
        Api cLeg = new Api(); cLeg.legacy = new Vector(); cLeg.legacy.addElement(L);
        Vector cl = cLeg.comments("abcDEF_-001");
        eq(Integer.valueOf(cl.size()), Integer.valueOf(1), "YtAPILegacy comments");
        eq(((String[]) cl.elementAt(0))[1], "Leg\u00e1l!", "legacy comment text"); eq(((String[]) cl.elementAt(0))[2], "3 days ago", "legacy age");
        Api cInv = new Api(); cInv.legacy = new Vector(); cInv.legacy.addElement(GONE); cInv.invidious.addElement(INV);
        Vector ci = cInv.comments("abcDEF_-001");
        eq(((String[]) ci.elementAt(0))[0], "Inv person", "Invidious comments after an unreachable server");
        eq(((String[]) ci.elementAt(0))[2], "5 hours ago", "zero likes not shown");
        Api cNone = new Api(); cNone.legacy = new Vector(); cNone.legacy.addElement(GONE);
        threw = false; try { cNone.comments("abcDEF_-001"); } catch (Exception e) { threw = e.getMessage().startsWith("No server had the comments"); }
        ok(threw, "no comments anywhere is reported");

        // --- a server that stalls mid-answer can't hold a request forever ---
        long st0 = System.currentTimeMillis();
        threw = false; try { Net.getText(L + "/stall", 1500); } catch (java.io.IOException e) { threw = e.getMessage().equals("no answer after 1 s"); }
        long stTook = System.currentTimeMillis() - st0;
        ok(threw && stTook < 3500, "stalled body times out (" + stTook + " ms)");
        Api stl = new Api(); stl.legacy = new Vector(); stl.legacy.addElement(L + "/stall?"); stl.invidious.addElement(INV);
        final Vector stSaid = new Vector();
        stl.progress = new Api.Progress() { public void say(String l) { stSaid.addElement(l); } };
        long keepT = Net.TIMEOUT;
        st0 = System.currentTimeMillis();
        Vector sr = stl.search("x");
        ok(System.currentTimeMillis() - st0 < Net.TIMEOUT + 4000, "search moves past a stalling server");
        eq(((Video) sr.elementAt(0)).title, "Inv result", "and gets results from the next one");
        eq(stSaid.elementAt(0), "Asking 127.0.0.1:18081", "progress names the server");

        // --- feeds: page 1 from any server, later pages from the same one ---
        Api fo = new Api(); fo.legacy = new Vector(); fo.legacy.addElement(L); fo.own = OWN; fo.invidious.addElement(INV);
        Feed fs = new Feed(Feed.SEARCH, "paged", "paged");
        fo.open(fs);
        eq(fs.kind, "T", "own server answers page 1"); eq(Integer.valueOf(fs.items.size()), Integer.valueOf(15), "15 a page");
        ok(!fs.end, "more to come");
        eq(((Video) fs.items.elementAt(0)).channel, "UCminemineminemineminem0", "channel id carried");
        eq(Integer.valueOf(fo.more(fs)), Integer.valueOf(15), "page 2"); eq(((Video) fs.items.elementAt(15)).title, "Own p2 #0", "page 2 content");
        fo.more(fs); eq(Integer.valueOf(fo.more(fs)), Integer.valueOf(0), "past the end"); ok(fs.end, "end reached");
        eq(Integer.valueOf(fs.items.size()), Integer.valueOf(45), "three pages in total");
        // YtAPILegacy has no pages: it is asked for more and the repeats are dropped
        Api fl = new Api(); fl.legacy = new Vector(); fl.legacy.addElement(L);
        Feed fls = new Feed(Feed.SEARCH, "paged", "paged");
        fl.open(fls); eq(Integer.valueOf(fls.items.size()), Integer.valueOf(15), "legacy page 1");
        eq(Integer.valueOf(fl.more(fls)), Integer.valueOf(15), "legacy page 2 only new entries");
        eq(Integer.valueOf(fl.more(fls)), Integer.valueOf(10), "legacy page 3 partial"); fl.more(fls); ok(fls.end, "legacy end");
        eq(((Video) fls.items.elementAt(39)).id, "legpage0039", "no repeats, in order");
        // Invidious pages by number for search
        Api fi = new Api(); fi.legacy = new Vector(); fi.invidious.addElement(INV);
        Feed fis = new Feed(Feed.SEARCH, "paged", "paged");
        fi.open(fis); fi.more(fis); eq(Integer.valueOf(fis.items.size()), Integer.valueOf(20), "invidious two pages");
        fi.more(fis); ok(fis.end, "invidious end");
        // related: own server has none, so it goes to YtAPILegacy (offset paging)
        Feed fr = new Feed(Feed.RELATED, "abcDEF_-001", "Related");
        fo.open(fr); eq(fr.kind, "L", "related skips own server"); eq(((Video) fr.items.elementAt(0)).id, "legrel00000", "legacy related");
        fo.more(fr); eq(((Video) fr.items.elementAt(15)).id, "legrel00015", "related page 2 by offset");
        fo.more(fr); ok(fr.end, "related end");
        Feed fir = new Feed(Feed.RELATED, "abcDEF_-001", "Related");
        fi.open(fir); eq(Integer.valueOf(fir.items.size()), Integer.valueOf(4), "invidious related"); ok(fir.end, "invidious related is one page");
        // channels
        Feed fc = new Feed(Feed.CHANNEL, "UCminemineminemineminem0", "Mine");
        fo.open(fc); eq(fc.kind, "T", "own channel"); fo.more(fc); eq(Integer.valueOf(fc.items.size()), Integer.valueOf(30), "own channel 2 pages");
        eq(((Video) fc.items.elementAt(0)).channel, "UCminemineminemineminem0", "channel videos know their channel");
        Feed flc = new Feed(Feed.CHANNEL, "@some.one", "Someone");
        fl.open(flc); eq(((Video) flc.items.elementAt(0)).id, "legchan0000", "legacy channel by handle");
        fl.more(flc); eq(Integer.valueOf(flc.items.size()), Integer.valueOf(20), "legacy channel more"); fl.more(flc); ok(flc.end, "legacy channel end");
        Feed fic = new Feed(Feed.CHANNEL, "UCinvinvinvinvinvinvinv0", "I");
        fi.open(fic); ok(!fic.end, "invidious channel continues"); fi.more(fic); eq(((Video) fic.items.elementAt(5)).id, "invchan0200", "continuation token used");
        ok(fic.end, "no token, no more");
        Feed fih = new Feed(Feed.CHANNEL, "@handle", "h");
        threw = false; try { fi.open(fih); } catch (Exception e) { threw = e.getMessage().indexOf("None of the servers") >= 0; }
        ok(threw, "a handle can't go to Invidious");
        // comments
        Feed fco = new Feed(Feed.COMMENTS, "abcDEF_-001", "Comments");
        fo.open(fco); eq(Integer.valueOf(fco.items.size()), Integer.valueOf(2), "own comments page 1");
        fo.more(fco); eq(((String[]) fco.items.elementAt(2))[1], "page two", "own comments page 2"); fo.more(fco); ok(fco.end, "own comments end");
        Feed fci = new Feed(Feed.COMMENTS, "abcDEF_-001", "Comments");
        fi.open(fci); ok(!fci.end, "invidious comments continue"); fi.more(fci);
        eq(((String[]) fci.items.elementAt(1))[2], "3 likes", "invidious comments page 2"); ok(fci.end, "invidious comments end");
        Feed fcl = new Feed(Feed.COMMENTS, "abcDEF_-001", "Comments");
        fl.open(fcl); ok(fcl.end, "legacy comments are one page");
        // channel references
        eq(Api.channelRef("https://www.youtube.com/@Some.Name-1/videos"), "@Some.Name-1", "handle from a URL");
        eq(Api.channelRef("UCabcdefghijklmnopqrstuv"), "UCabcdefghijklmnopqrstuv", "UC id");
        eq(Api.channelRef("/channel/UCabcdefghijklmnopqrstuv"), "UCabcdefghijklmnopqrstuv", "UC id from a path");
        eq(Api.channelRef("UCshort"), "", "not an id"); eq(Api.channelRef(null), "", "null");
        eq(Util.percentDecode("%40name"), "@name", "percent decode");
        eq(((Video) fo.search("x").elementAt(0)).channel, "UCminemineminemineminem0", "search results carry the channel");
        eq(((Video) fl.search("gatos").elementAt(0)).channel, "UCcanalcanalcanalcanal00", "legacy results carry the channel");

        // --- public TubeME servers from the list, keys in the address, the server's own error text ---
        String PUB = "http://127.0.0.1:18089/k/abcd";
        Api pb = new Api();
        eq(Integer.valueOf(pb.updateInstances(L + "/list2.json")), Integer.valueOf(2), "list with a TubeME server");
        eq(pb.tubeme.elementAt(0), PUB, "its address keeps the key path");
        ok(pb.ping(PUB), "public TubeME server pings (reads what it makes)");
        boolean listed = false;
        for (int i = 0; i < pb.servers().size(); i++) listed |= ((String[]) pb.servers().elementAt(i))[0].equals("TubeME");
        ok(listed, "shown on the Servers screen");
        pb.legacy = new Vector(); pb.legacy.addElement(GONE);
        eq(((Video) pb.search("x").elementAt(0)).title, "Public result", "search through the public server");
        Video pv = new Video(); pv.id = "abcDEF_-001";
        Api.Stream ps2 = pb.stream(pv, Api.Q_MP4V_240, 0);
        eq(ps2.url, PUB + "/file/abcDEF_-001-mp4v-240.mp4", "file address keeps the key");
        eq(Integer.valueOf(Net.get(ps2.url, 200000).length), Integer.valueOf(76800), "and downloads");
        eq(Integer.valueOf(pb.serversFor(Api.Q_H264_240)), Integer.valueOf(0), "qualities it doesn't make aren't counted");
        threw = false; try { pb.search("busy"); } catch (Exception e) { threw = e.getMessage().indexOf("try again in 42 s") >= 0; }
        ok(threw, "a refusal shows the server's own reason");
        Api locked = new Api(); locked.legacy = new Vector(); locked.own = "http://127.0.0.1:18089";
        ok(!locked.ping("http://127.0.0.1:18089"), "a locked server without its key counts as down");
        threw = false; try { Net.getText("http://127.0.0.1:18089/search?q=x"); } catch (java.io.IOException e) { threw = e.getMessage().equals("HTTP 403: this server needs a key"); }
        ok(threw, "403 carries the reason");

        // --- fastest server first ---
        Api rk = new Api(); rk.legacy = new Vector(); rk.legacy.addElement(L); rk.legacy.addElement(GONE);
        rk.yt2009.addElement(Y); rk.yt2009.addElement(YBAD); rk.invidious.addElement(INV);
        rk.markGood(L); rk.ms.put(L, "500"); rk.markGood(Y); rk.ms.put(Y, "20"); rk.markGood(INV); rk.ms.put(INV, "90");
        rk.ping(GONE);
        for (int i = 0; i < 5; i++) {
            Video rv = new Video(); rv.id = "abcDEF_-00" + i;
            eq(rk.stream(rv, Api.Q_MP4V, 0).server, Y, "fastest server streams first, run " + i);
        }
        Vector sv = rk.servers();
        eq(((String[]) sv.elementAt(0))[1], Y, "server list: fastest first");
        eq(((String[]) sv.elementAt(1))[1], INV, "server list: second fastest");
        eq(((String[]) sv.elementAt(2))[1], L, "server list: slowest tested");
        eq(((String[]) sv.elementAt(3))[1], YBAD, "server list: untested after tested");
        eq(((String[]) sv.elementAt(4))[1], GONE, "server list: down last");
        eq(((Video) rk.search("x").elementAt(0)).source, L, "metadata prefers YtAPILegacy while it is up");

        // --- several lists merged; a dead list costs nothing; built-in servers always stay ---
        String PIPB = "http://127.0.0.1:18090";
        Api ml = new Api();
        Vector srcs = new Vector(); srcs.addElement(GONE + "/list.json"); srcs.addElement(L + "/notPipe.json"); srcs.addElement(L + "/list3.json");
        ml.updateInstances(srcs);
        eq(Integer.valueOf(ml.invidious.size()), Integer.valueOf(2), "invidious from both lists, no duplicates");
        eq(Integer.valueOf(ml.piped.size()), Integer.valueOf(2), "piped from both lists");
        eq(ml.piped.elementAt(1), PIPB, "piped proxy part dropped");
        Vector badL = new Vector(); badL.addElement(GONE + "/list.json");
        threw = false; try { ml.updateInstances(badL); } catch (Exception e) { threw = true; }
        ok(threw && ml.piped.size() == 2, "no list at all: error, old servers kept");
        Api sd = new Api(); sd.seed();
        ok(sd.invidious.contains("http://s60tube.io.vn"), "built-in S60Tube instance");
        ok(sd.piped.size() >= 10 && sd.yt2009.size() >= 2, "built-in Piped and yt2009 servers");
        sd.updateInstances(L + "/legacyonly.json");
        ok(sd.invidious.contains("http://s60tube.io.vn") && sd.piped.size() >= 10, "built-ins survive a thin list");

        // --- Piped ---
        Api pp = new Api(); pp.legacy = new Vector(); pp.piped = new Vector(); pp.piped.addElement(PIPB);
        Feed pps = new Feed(Feed.SEARCH, "cats", "cats");
        pp.open(pps);
        eq(pps.kind, "P", "search from Piped"); eq(Integer.valueOf(pps.items.size()), Integer.valueOf(5), "channels left out");
        Video pv0 = (Video) pps.items.elementAt(0);
        eq(pv0.id, "pips00000", "id from /watch?v="); eq(pv0.duration, "2:05", "duration"); eq(pv0.channel, "UCpippippippippippippip0", "channel");
        ok(!pps.end, "more pages via nextpage");
        eq(Integer.valueOf(pp.more(pps)), Integer.valueOf(3), "page 2"); ok(pps.end, "no nextpage: end");
        Feed pr = new Feed(Feed.RELATED, "abcDEF_-001", "Related"); pp.open(pr);
        eq(Integer.valueOf(pr.items.size()), Integer.valueOf(6), "related from /streams"); ok(pr.end, "related one page");
        Feed pop = new Feed(Feed.POPULAR, "", "Popular"); pp.open(pop); eq(Integer.valueOf(pop.items.size()), Integer.valueOf(4), "popular from hype playlist");
        Feed pc = new Feed(Feed.COMMENTS, "abcDEF_-001", "Comments"); pp.open(pc);
        eq(((String[]) pc.items.elementAt(0))[1], "hi & bye", "comment html cleaned");
        eq(((String[]) pc.items.elementAt(0))[2], "2 likes, 1 day ago", "comment extra");
        pp.more(pc); eq(((String[]) pc.items.elementAt(1))[2], "", "hidden likes not shown"); ok(pc.end, "comments end");
        Video pi = pp.info("abcDEF_-001");
        eq(pi.title, "Pip title", "info title"); eq(pi.stream, "http://v/360", "muxed 360p stream picked");
        eq(pi.description, "line1\nline2 & more", "description as text");
        eq(pp.stream(pi, Api.Q_H264, 0).url, "http://v/360", "Piped stream plays as H.264 360p");

        // --- your own server is never written off ---
        Api pin = new Api(); pin.legacy = new Vector(); pin.legacy.addElement(L); pin.own = OWN;
        pin.ping(GONE); // unrelated
        Api.RETRY_DEAD_AFTER = 600000;
        Api pin2 = new Api(); pin2.legacy = new Vector(); pin2.legacy.addElement(L); pin2.own = GONE;
        pin2.sweep(true);
        eq(pin2.status(GONE), "not tested", "own server failing a ping isn't marked down");
        Feed pf = new Feed(Feed.SEARCH, "x", "x"); pin2.open(pf); eq(pf.kind, "L", "falls back when own is off");
        pin2.own = OWN;
        Feed pf2 = new Feed(Feed.SEARCH, "x", "x"); pin2.open(pf2); eq(pf2.kind, "T", "own server asked first again");

        // --- files: folder pick, .ram, download to file ---
        new java.io.File("/tmp/tubefs/E:").mkdirs();
        String dir = Files.pickDir("");
        eq(dir, "file:///E:/", "picked folder");
        threw = false; try { Files.pickDir("file:///DENY/"); } catch (Exception e) { threw = true; }
        ok(!threw, "bad preferred folder falls back");
        String ram = Files.writeRam(dir, "http://x/y?a=1&b=2");
        eq(ram, "file:///E:/tubeme1.ram", "ram path");
        eq(new String(java.nio.file.Files.readAllBytes(new java.io.File("/tmp/tubefs/E:/tubeme1.ram").toPath())), "http://x/y?a=1&b=2\n", "ram content");
        eq(Files.writeRam(dir, "http://x/z"), "file:///E:/tubeme2.ram", "ram name rotates");
        HttpConnection c = Net.open(only.stream(v, Api.Q_H263, 0).url);
        ok(c.getLength() <= 0, "conversion stream has no length");
        java.io.OutputStream out = Files.openOut(dir + "TubeME_x.3gp"); java.io.InputStream in = c.openInputStream();
        byte[] buf = new byte[4096]; int k; long tot = 0; while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); tot += k; }
        in.close(); out.close(); Files.closeOut(); Net.close(c);
        eq(Long.valueOf(new java.io.File("/tmp/tubefs/E:/TubeME_x.3gp").length()), Long.valueOf(76800), "downloaded file size");
        Files.delete(dir + "TubeME_x.3gp");
        ok(!new java.io.File("/tmp/tubefs/E:/TubeME_x.3gp").exists(), "delete works");
        ok(!new java.io.File("/tmp/tubefs/E:/tubeme.tmp").exists(), "probe file cleaned up");
    }
}
