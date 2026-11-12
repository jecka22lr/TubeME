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
        Api walk = new Api(); walk.legacy = api.legacy; walk.yt2009 = new Vector(); walk.yt2009.addElement(Y);
        Video wv = new Video(); wv.id = "abcDEF_-001";
        Hashtable seen = new Hashtable(); int skip = 0;
        for (int i = 0; i < 6; i++) { Api.Stream s = walk.stream(wv, Api.Q_MP4V, skip); seen.put(s.server, ""); skip = s.next; }
        eq(Integer.valueOf(walk.streamServers()), Integer.valueOf(3), "stream server count");
        eq(Integer.valueOf(seen.size()), Integer.valueOf(3), "other-server visits all");
        Api.Stream d1 = inv.stream(iv, Api.Q_H264, 1);
        eq(d1.url, iv.stream, "invidious direct stream as last resort");

        // --- files: folder pick, .ram, download to file ---
        new java.io.File("/tmp/tubefs/E:").mkdirs();
        String dir = Files.pickDir("");
        eq(dir, "file:///E:/", "picked folder");
        threw = false; try { Files.pickDir("file:///DENY/"); } catch (Exception e) { threw = true; }
        ok(!threw, "bad preferred folder falls back");
        String ram = Files.writeRam(dir, "http://x/y?a=1&b=2");
        eq(ram, "file:///E:/tubeme.ram", "ram path");
        eq(new String(java.nio.file.Files.readAllBytes(new java.io.File("/tmp/tubefs/E:/tubeme.ram").toPath())), "http://x/y?a=1&b=2\n", "ram content");
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
