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
        eq(Integer.valueOf(qc6.length), Integer.valueOf(5), "S60 5th quality count");
        for (int i = 0; i < qc6.length; i++) ok(qc6[i] != Api.Q_H264, "H.264 360p hidden on S60 5th");
        ok(qc6[1] == Api.Q_H264_144, "H.264 144p still offered on S60 5th");
        eq(Integer.valueOf(Api.qualities("NokiaN8-00/x/sw_platform=S60;sw_platform_version=5.2").length), Integer.valueOf(6), "all offered on Symbian^3");
        eq(Integer.valueOf(Api.qualities(null).length), Integer.valueOf(6), "all offered when platform unknown");
        eq(Integer.valueOf(Api.Q_NAMES.length), Integer.valueOf(6), "a name per quality id");

        // --- instance list ---
        Api api = new Api();
        eq(Integer.valueOf(api.updateInstances(L + "/notPipe.json")), Integer.valueOf(5), "instance list count");
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
        for (int i = 0; i < sm.length; i++) ok(sm[i] != Settings.MODE_EXTERNAL, "browser route not offered on Symbian");
        for (int i = 0; i < om.length; i++) ok(om[i] != Settings.MODE_REAL, "RealPlayer not offered off Symbian");
        ok(sm[0] == Settings.MODE_AUTO && om[0] == Settings.MODE_AUTO, "Auto first on both");
        eq(Settings.modeNames(false)[2], "Download, then play", "other phones keep the plain name");
        Video qv3 = new Video(); qv3.id = "abcDEF_-003";
        threw = false; try { old.stream(qv3, Api.Q_H264_144, 0); } catch (Exception e) { threw = e.getMessage().indexOf("needs a sign-in") >= 0; }
        ok(threw, "sign-in redirect is not mistaken for a video");

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
