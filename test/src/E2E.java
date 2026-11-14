package tube;
import java.util.*;

/**
 * End to end with real YouTube: the phone's own code (Api, Net, Json) against a real
 * TubeME server running yt-dlp and ffmpeg. Run by test/e2e.sh (CI does it on every push).
 * Your server's part must pass; the public services are only reported.
 */
public class E2E {
    static int n, bad;
    static void ok(boolean c, String what) { n++; System.out.println((c ? "ok " : "FAIL ") + what); if (!c) bad++; }
    static long t0;
    static String took() { return " (" + (System.currentTimeMillis() - t0) / 1000 + " s)"; }

    public static void main(String[] a) {
        String own = a.length > 0 ? a[0] : "http://127.0.0.1:8765";
        try { run(own); } catch (Throwable t) { t.printStackTrace(); bad++; }
        System.out.println(bad == 0 ? "E2E PASSED (" + n + " checks)" : "E2E FAILED (" + bad + " of " + n + ")");
        System.exit(bad == 0 ? 0 : 1);
    }

    static Api api(String own) {
        Api x = new Api();
        x.legacy = new Vector();
        x.own = own;
        x.progress = new Api.Progress() { public void say(String l) { System.out.println("   phone says: " + l); } };
        return x;
    }

    static void run(String own) throws Exception {
        Api api = api(own);
        // like a phone started before the server: the first check fails
        api.sweep(true);
        t0 = System.currentTimeMillis();
        boolean up = false;
        while (!up && System.currentTimeMillis() - t0 < 600000) {
            up = api.ping(own);
            if (!up) Thread.sleep(3000);
        }
        ok(up, "your server answers" + took());

        t0 = System.currentTimeMillis();
        Feed s = new Feed(Feed.SEARCH, "nokia 6600", "s");
        api.open(s);
        ok("T".equals(s.kind) && s.items.size() > 0, "search comes from your server (it failed a check earlier): " + s.items.size() + " results" + took());
        t0 = System.currentTimeMillis();
        int more = api.more(s);
        ok(more > 0, "search page 2: " + more + took());

        t0 = System.currentTimeMillis();
        Feed p = new Feed(Feed.POPULAR, "", "p");
        api.open(p);
        ok("T".equals(p.kind) && p.items.size() > 0, "popular: " + p.items.size() + took());

        String id = "jNQXAC9IVRw"; // "Me at the zoo", 19 seconds
        t0 = System.currentTimeMillis();
        Video v = api.info(id);
        ok(v.title.length() > 0 && v.duration.length() > 0, "video details: " + v.title + " " + v.duration + took());

        t0 = System.currentTimeMillis();
        Feed c = new Feed(Feed.COMMENTS, id, "c");
        try { api.open(c); } catch (Exception e) { System.out.println("   " + e.getMessage()); }
        ok(c.items.size() > 0, "comments: " + c.items.size() + took());

        int[] qs = { Api.Q_MP4V_360, Api.Q_H263_144, Api.Q_MP4V_240, Api.Q_H264, Api.Q_AUDIO };
        for (int i = 0; i < qs.length; i++) {
            t0 = System.currentTimeMillis();
            String what = "play " + Api.Q_NAMES[qs[i]];
            try {
                Api.Stream st = api.stream(v, qs[i], 0);
                ok(st.url.startsWith(own), what + ": your server made it" + took());
                String why = Net.probe(st.url);
                ok(why == null, what + ": the file is real video" + (why == null ? "" : " (" + why + ")"));
            } catch (Exception e) {
                ok(false, what + ": " + e.getMessage() + took());
            }
        }

        // timing on a normal-length video, the way it's used: open the page, read for 15 s, press Play
        System.out.println("--- timing, 3.5 minute video (report only) ---");
        try {
            Video v2 = api.info("dQw4w9WgXcQ");
            t0 = System.currentTimeMillis();
            api.warm(v2, Api.Q_MP4V_360);
            Thread.sleep(15000);
            long play = System.currentTimeMillis();
            Api.Stream st = api.stream(v2, Api.Q_MP4V_360, 0);
            System.out.println("   ready " + (System.currentTimeMillis() - play) / 1000 + " s after pressing Play (" + took().trim() + " since the page opened)");
            t0 = System.currentTimeMillis();
            Api.Stream st2 = api.stream(v2, Api.Q_H263_144, 0);
            System.out.println("   another quality of the same video: " + took().trim());
        } catch (Exception e) { System.out.println("   " + e.getMessage()); }

        System.out.println("--- timing, 4.7 minute video, Play pressed at once (report only) ---");
        try {
            Video v3 = api.info("kJQP7kiw5Fk");
            t0 = System.currentTimeMillis();
            api.stream(v3, Api.Q_MP4V_360, 0);
            System.out.println("   MPEG-4 360p ready after " + took().trim());
            t0 = System.currentTimeMillis();
            api.stream(v3, Api.Q_H263_144, 0);
            System.out.println("   H.263 144p of the same video after " + took().trim());
        } catch (Exception e) { System.out.println("   " + e.getMessage()); }

        // the public services: reported, never failing the run (they come and go)
        System.out.println("--- public services (report only) ---");
        Api seeds = new Api(); seeds.seed();
        report("YtAPILegacy", "L", seeds.legacy, id);
        report("Invidious", "I", seeds.invidious, id);
        report("Piped", "P", seeds.piped, id);
        Vector lists = new Vector(); lists.addElement(Api.DEFAULT_LIST_URL); lists.addElement(Api.GITHUB_LIST_URL); lists.addElement(own + "/servers");
        for (int i = 0; i < lists.size(); i++) {
            Api la = new Api();
            String u = (String) lists.elementAt(i);
            try { System.out.println("   list " + u + ": " + la.updateInstances(u) + " servers"); }
            catch (Exception e) { System.out.println("   list " + u + ": " + e.getMessage()); }
        }
    }

    static void report(String name, String kind, Vector bases, String id) {
        for (int i = 0; i < bases.size(); i++) {
            String b = (String) bases.elementAt(i);
            Api x = new Api(); x.legacy = new Vector();
            if (kind.equals("L")) x.legacy.addElement(b);
            if (kind.equals("I")) x.invidious.addElement(b);
            if (kind.equals("P")) x.piped.addElement(b);
            long t = System.currentTimeMillis();
            String r;
            try {
                Feed f = new Feed(Feed.SEARCH, "nokia", "x");
                x.open(f);
                r = "search " + f.items.size();
                try {
                    Video v = x.info(id);
                    r += ", details ok" + (v.stream != null && v.stream.length() > 0 ? ", has 360p link" : "");
                } catch (Exception e) { r += ", details: " + e.getMessage(); }
            } catch (Exception e) { r = e.getMessage(); }
            System.out.println("   " + name + " " + b + ": " + r + " (" + (System.currentTimeMillis() - t) / 1000 + " s)");
        }
    }
}
