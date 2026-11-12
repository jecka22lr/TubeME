package tube;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/** UI and flow. All network and file work happens on worker threads. */
public class TubeMIDlet extends MIDlet implements CommandListener, Api.Progress {
    private static final int T_SEARCH = 1, T_POPULAR = 2, T_INFO = 3, T_PLAY = 4, T_DOWNLOAD = 5,
            T_UPDATE = 6, T_THUMB = 7, T_PLAYFILE = 8, T_LOGFILE = 9, T_SWEEP = 10;
    private static final long UPDATE_EVERY = 3L * 24 * 3600 * 1000;

    private Display display;
    private final Api api = new Api();
    private final Settings settings = new Settings();
    private boolean started;

    private List menu;
    private List results;
    private Vector items = new Vector();
    private Form videoForm;
    private Video cur;
    /** The in-app player screen while one is open (a PlayerCanvas, kept untyped on purpose). */
    private Displayable player;

    /** Bumped whenever the user cancels or moves on; stale workers check it and stop. */
    private int gen;
    /** Which server to start from; "Other server" moves it on. */
    private int skip;
    private int lastNext;
    private String savedPath;
    private String dirOk;

    private Form waitForm;
    private StringItem waitText;

    private final Command cBack = new Command("Back", Command.BACK, 2);
    private final Command cExit = new Command("Exit", Command.EXIT, 3);
    private final Command cOk = new Command("OK", Command.OK, 1);
    private final Command cCancel = new Command("Cancel", Command.CANCEL, 1);
    private final Command cPlay = new Command("Play", Command.OK, 1);
    private final Command cDownload = new Command("Save", "Download", Command.SCREEN, 2);
    private final Command cOther = new Command("Server", "Other server", Command.SCREEN, 3);
    private final Command cQuality = new Command("Quality", Command.SCREEN, 4);
    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cPlayFile = new Command("Play", Command.OK, 1);

    private TextBox searchBox;
    private TextBox idBox;
    private Form settingsForm;
    private ChoiceGroup sMode, sQuality, sOpts;
    private TextField sList, sCustom, sDir;
    private List qualityList;
    private Form savedForm;
    private Form logForm;
    private List serverList;
    private final Command cTest = new Command("Test", "Test again", Command.OK, 1);
    private final Command cUpdate = new Command("Update", "Update list", Command.SCREEN, 2);
    private final Command cLogFile = new Command("To file", "Save log to file", Command.SCREEN, 2);

    protected void startApp() {
        if (started) return;
        started = true;
        display = Display.getDisplay(this);
        Log.init();
        // first-run default: S60 plays MPEG-4 up to 640x360 but H.264 only to QVGA
        if (Util.isSymbian()) settings.quality = Api.Q_MP4V_360;
        settings.load(api);
        api.progress = this;
        Log.add("TubeME " + Api.VERSION + " on " + System.getProperty("microedition.platform"));
        Log.add("symbian=" + Util.isSymbian() + " files=" + Util.hasFiles() + " mmapi=" + System.getProperty("microedition.media.version")
                + " heap=" + (Runtime.getRuntime().totalMemory() / 1024) + "K mode=" + settings.mode + " q=" + settings.quality);
        // optional JAD/manifest overrides, so servers can be changed without rebuilding
        String p = getAppProperty("TubeME-List-URL");
        if (p != null && p.trim().length() > 0 && settings.listUrl.equals(Api.DEFAULT_LIST_URL)) settings.listUrl = p.trim();
        p = getAppProperty("TubeME-Server");
        if (p != null && p.trim().length() > 0 && api.custom.length() == 0) api.custom = p.trim();
        menu = new List("TubeME", Choice.IMPLICIT);
        menu.append("Search", null);
        menu.append("Popular", null);
        menu.append("Open ID or link", null);
        menu.append("Settings", null);
        menu.append("Servers", null);
        menu.append("About", null);
        menu.append("Log", null);
        menu.addCommand(cExit);
        menu.setCommandListener(this);
        display.setCurrent(menu);
        // every start: find out which servers this phone can actually reach right now
        new Thread(new Runnable() {
            public void run() {
                try {
                    freshServers(0);
                    api.sweep(false);
                } catch (Throwable t) {
                    Log.err("startup sweep", t);
                }
            }
        }).start();
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
        gen++;
        if (player != null) PlayerCanvas.stop(player);
    }

    // ---- small UI helpers ----

    private void show(Displayable d) {
        display.setCurrent(d);
    }

    private void alert(String title, String text, Displayable next) {
        Alert a = new Alert(title, text, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, next);
    }

    private void info(String title, String text, Displayable next) {
        Alert a = new Alert(title, text, null, AlertType.INFO);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, next);
    }

    /**
     * Shows the busy screen. It is one plain text form, made once and reused:
     * building a new form with an animated gauge per task is the prime
     * suspect for the app being killed on S60 after a few tasks.
     */
    private void busy(String what) {
        if (waitForm == null) {
            waitForm = new Form("Please wait");
            waitText = new StringItem(null, what);
            waitForm.append(waitText);
            waitForm.addCommand(cCancel);
            waitForm.setCommandListener(this);
        }
        waitLines.removeAllElements();
        say(what);
        show(waitForm);
    }

    private final Vector waitLines = new Vector();

    /**
     * Adds a line to the wait screen (newest at the bottom, last few kept),
     * so a long wait shows what is going on instead of one frozen sentence.
     */
    public synchronized void say(String line) {
        if (waitText == null) return;
        waitLines.addElement(line);
        while (waitLines.size() > 7) waitLines.removeElementAt(0);
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < waitLines.size(); i++) {
            if (i > 0) b.append('\n');
            b.append((String) waitLines.elementAt(i));
        }
        waitText.setText(b.toString());
    }

    private void run(int kind, Object arg, String what) {
        int g = ++gen;
        busy(what);
        new Thread(new Task(kind, arg, g)).start();
    }

    private Displayable home() {
        if (videoForm != null && cur != null) return videoForm;
        if (results != null) return results;
        return menu;
    }

    // ---- commands ----

    public void commandAction(Command c, Displayable d) {
        if (d == menu) {
            if (c == cExit) {
                destroyApp(true);
                notifyDestroyed();
                return;
            }
            switch (menu.getSelectedIndex()) {
                case 0:
                    if (searchBox == null) {
                        searchBox = new TextBox("Search", "", 100, TextField.ANY);
                        searchBox.addCommand(cOk);
                        searchBox.addCommand(cBack);
                        searchBox.setCommandListener(this);
                    }
                    show(searchBox);
                    break;
                case 1:
                    run(T_POPULAR, null, "Loading popular videos...");
                    break;
                case 2:
                    if (idBox == null) {
                        idBox = new TextBox("Video ID or link", "", 200, TextField.ANY);
                        idBox.addCommand(cOk);
                        idBox.addCommand(cBack);
                        idBox.setCommandListener(this);
                    }
                    show(idBox);
                    break;
                case 3:
                    showSettings();
                    break;
                case 4:
                    showServers();
                    break;
                case 5:
                    showAbout();
                    break;
                case 6:
                    showLog();
                    break;
            }
        } else if (d == searchBox) {
            if (c == cOk && searchBox.getString().trim().length() > 0) {
                run(T_SEARCH, searchBox.getString().trim(), "Searching...");
            } else {
                show(menu);
            }
        } else if (d == idBox) {
            if (c == cOk) {
                String id = Util.videoId(idBox.getString());
                if (id == null) alert("Not a video", "That doesn't look like a video ID or link.", idBox);
                else {
                    results = null;
                    run(T_INFO, id, "Loading video...");
                }
            } else {
                show(menu);
            }
        } else if (d == results) {
            if (c == cBack) {
                results = null;
                show(menu);
            } else {
                int i = results.getSelectedIndex();
                if (i >= 0 && i < items.size()) run(T_INFO, items.elementAt(i), "Loading video...");
            }
        } else if (d == videoForm) {
            if (c == cBack) {
                gen++;
                videoForm = null;
                cur = null;
                show(results != null ? (Displayable) results : menu);
            } else if (c == cPlay) {
                play();
            } else if (c == cDownload) {
                run(T_DOWNLOAD, Boolean.FALSE, "Preparing download...");
            } else if (c == cOther) {
                skip = lastNext;
                play();
            } else if (c == cQuality) {
                qualityList = new List("Quality", Choice.IMPLICIT, Api.Q_NAMES, null);
                qualityList.setSelectedIndex(settings.quality, true);
                qualityList.addCommand(cBack);
                qualityList.setCommandListener(this);
                show(qualityList);
            }
        } else if (d == qualityList) {
            if (c != cBack) {
                settings.quality = qualityList.getSelectedIndex();
                settings.save(api);
                skip = 0;
            }
            show(home());
        } else if (d == waitForm) {
            gen++;
            show(home());
        } else if (d == settingsForm) {
            if (c == cSave) saveSettings();
            show(menu);
        } else if (d == serverList) {
            if (c == cTest) run(T_SWEEP, null, "Testing servers...");
            else if (c == cUpdate) run(T_UPDATE, null, "Updating server list...");
            else if (c == cBack) show(menu);
        } else if (d == logForm) {
            if (c == cLogFile) run(T_LOGFILE, null, "Saving log...");
            else show(menu);
        } else if (d == savedForm) {
            if (c == cPlayFile && savedPath != null) run(T_PLAYFILE, savedPath, "Opening...");
            else show(home());
        } else if (c == cBack) {
            show(home());
        }
    }

    /** Says so up front when the chosen quality is one this phone is known not to show. */
    private void codecNote() {
        if (settings.quality == Api.Q_H264 && Util.h264OnlyQvga(System.getProperty("microedition.platform"))) {
            say("Note: this phone only decodes H.264 up to 320x240, so 360p will likely be sound without picture. MPEG-4 360p is the one to use.");
        }
    }

    private void play() {
        if (mode() == Settings.MODE_DOWNLOAD) run(T_DOWNLOAD, Boolean.TRUE, "Preparing download...");
        else run(T_PLAY, null, "Finding a server...");
    }

    // ---- capability checks ----

    private static boolean hasVideoPlayer() {
        if (System.getProperty("microedition.media.version") == null) return false;
        try {
            return PlayerCanvas.hasVideo();
        } catch (Throwable t) {
            return false;
        }
    }

    /** What "Auto" turns into on this phone. */
    private int mode() {
        int m = settings.mode;
        boolean files = Util.hasFiles();
        if (m == Settings.MODE_AUTO) {
            // RealPlayer is happiest with a file on the card; streaming stays a manual choice
            if (files) m = Settings.MODE_DOWNLOAD;
            else if (Util.isSymbian()) m = Settings.MODE_EXTERNAL;
            else if (hasVideoPlayer()) m = Settings.MODE_INAPP;
            else m = Settings.MODE_EXTERNAL;
        }
        if (m == Settings.MODE_REAL && !files) m = Settings.MODE_EXTERNAL;
        if (m == Settings.MODE_DOWNLOAD && !files) m = hasVideoPlayer() ? Settings.MODE_INAPP : Settings.MODE_EXTERNAL;
        return m;
    }

    // ---- screens ----

    private void showSettings() {
        settingsForm = new Form("Settings");
        sMode = new ChoiceGroup("Playback", Choice.POPUP, Settings.MODE_NAMES, null);
        sMode.setSelectedIndex(settings.mode, true);
        sQuality = new ChoiceGroup("Quality", Choice.POPUP, Api.Q_NAMES, null);
        sQuality.setSelectedIndex(settings.quality, true);
        sOpts = new ChoiceGroup(null, Choice.MULTIPLE, new String[] { "Show thumbnails" }, null);
        sOpts.setSelectedIndex(0, settings.thumbs);
        sList = new TextField("Server list URL", settings.listUrl, 200, TextField.URL);
        sCustom = new TextField("My YtAPILegacy server (optional)", api.custom, 200, TextField.URL);
        sDir = new TextField("Download folder (empty = auto)", settings.dir, 200, TextField.ANY);
        settingsForm.append(sMode);
        settingsForm.append(sQuality);
        settingsForm.append(sOpts);
        settingsForm.append(sList);
        settingsForm.append(sCustom);
        settingsForm.append(sDir);
        settingsForm.addCommand(cSave);
        settingsForm.addCommand(cBack);
        settingsForm.setCommandListener(this);
        show(settingsForm);
    }

    private void saveSettings() {
        settings.mode = sMode.getSelectedIndex();
        settings.quality = sQuality.getSelectedIndex();
        settings.thumbs = sOpts.isSelected(0);
        String l = sList.getString().trim();
        settings.listUrl = l.length() == 0 ? Api.DEFAULT_LIST_URL : l;
        api.custom = sCustom.getString().trim();
        String d = sDir.getString().trim();
        if (!d.equals(settings.dir)) dirOk = null;
        settings.dir = d;
        settings.save(api);
    }

    private void showAbout() {
        Form f = new Form("About");
        f.append(new StringItem(null, "TubeME " + Api.VERSION + "\nYouTube for J2ME through YtAPILegacy, yt2009 and Invidious servers.\n"));
        f.append(new StringItem("Platform", String.valueOf(System.getProperty("microedition.platform"))));
        f.append(new StringItem("Symbian", Util.isSymbian() ? "yes" : "no"));
        f.append(new StringItem("File access", Util.hasFiles() ? "yes" : "no"));
        f.append(new StringItem("In-app video", hasVideoPlayer() ? "yes" : "no"));
        f.append(new StringItem("Auto playback", Settings.MODE_NAMES[mode()]));
        f.append(new StringItem("Servers", api.legacy.size() + " YtAPILegacy, " + api.yt2009.size() + " yt2009, "
                + api.invidious.size() + " Invidious"));
        f.append(new StringItem("Memory", (Runtime.getRuntime().totalMemory() / 1024) + " KB"));
        f.addCommand(cBack);
        f.setCommandListener(this);
        show(f);
    }

    private void showServers() {
        serverList = new List("Servers", Choice.IMPLICIT);
        Vector v = api.servers();
        for (int i = 0; i < v.size(); i++) {
            String[] e = (String[]) v.elementAt(i);
            String host = e[1].substring(e[1].indexOf("://") + 3);
            serverList.append(api.status(e[1]) + " | " + host + " (" + e[0] + ")", null);
        }
        try {
            serverList.setFitPolicy(Choice.TEXT_WRAP_ON);
        } catch (Throwable t) {
        }
        serverList.setSelectCommand(cTest);
        serverList.addCommand(cUpdate);
        serverList.addCommand(cBack);
        serverList.setCommandListener(this);
        show(serverList);
    }

    private void showLog() {
        logForm = new Form("Log");
        if (Log.hasPrevious()) logForm.append(new StringItem("Previous session", Log.text(true)));
        logForm.append(new StringItem("This session", Log.text(false)));
        logForm.addCommand(cBack);
        if (Util.hasFiles()) logForm.addCommand(cLogFile);
        logForm.setCommandListener(this);
        show(logForm);
    }

    private void showResults(String title, Vector v) {
        items = v;
        results = new List(title, Choice.IMPLICIT);
        for (int i = 0; i < v.size(); i++) results.append(((Video) v.elementAt(i)).line(), null);
        try {
            results.setFitPolicy(Choice.TEXT_WRAP_ON);
        } catch (Throwable t) {
        }
        results.addCommand(cBack);
        results.setCommandListener(this);
        show(results);
    }

    private void showVideo(Video v, int g) {
        cur = v;
        skip = 0;
        lastNext = 0;
        Form f = new Form(v.title.length() > 0 ? v.title : v.id);
        f.append(new StringItem(null, v.title + "\n"));
        if (v.author.length() > 0) f.append(new StringItem("By", v.author));
        if (v.duration.length() > 0) f.append(new StringItem("Length", v.duration));
        if (v.views.length() > 0) f.append(new StringItem("Views", v.views));
        if (v.published.length() > 0) f.append(new StringItem("Published", v.published));
        String d = v.description;
        if (d.length() > 600) d = d.substring(0, 600) + "...";
        if (d.length() > 0) f.append(new StringItem(null, "\n" + d));
        f.addCommand(cPlay);
        if (Util.hasFiles()) f.addCommand(cDownload);
        f.addCommand(cQuality);
        f.addCommand(cOther);
        f.addCommand(cBack);
        f.setCommandListener(this);
        videoForm = f;
        show(f);
        // (no heap check: totalMemory() is only the current heap on S60, not the limit;
        // a failed decode is caught and logged by the task)
        if (settings.thumbs) {
            new Thread(new Task(T_THUMB, v, g)).start();
        }
    }

    void playerClosed() {
        player = null;
        show(home());
    }

    private void showSaved(String path, long bytes) {
        savedPath = path;
        savedForm = new Form("Saved");
        savedForm.append(new StringItem(null, (bytes / 1024) + " KB saved to\n" + path.substring(8)));
        savedForm.addCommand(cPlayFile);
        savedForm.addCommand(cBack);
        savedForm.setCommandListener(this);
        show(savedForm);
    }

    // ---- workers ----

    private final class Task implements Runnable {
        private final int kind;
        private final Object arg;
        private final int g;

        Task(int kind, Object arg, int g) {
            this.kind = kind;
            this.arg = arg;
            this.g = g;
        }

        public void run() {
            if (kind != T_THUMB) Log.add("task " + kind + " start");
            try {
                switch (kind) {
                    case T_SEARCH: {
                        freshServers(g);
                        Vector v = api.search((String) arg);
                        if (g != gen) return;
                        if (v.size() == 0) info("Search", "Nothing found.", menu);
                        else showResults((String) arg, v);
                        break;
                    }
                    case T_POPULAR: {
                        freshServers(g);
                        Vector v = api.popular();
                        if (g != gen) return;
                        if (v.size() == 0) info("Popular", "The server returned no videos.", menu);
                        else showResults("Popular", v);
                        break;
                    }
                    case T_INFO: {
                        freshServers(g);
                        String id = arg instanceof Video ? ((Video) arg).id : (String) arg;
                        Video v = api.info(id);
                        if (g != gen) return;
                        if (arg instanceof Video) {
                            // search results sometimes know more than the info call
                            Video s = (Video) arg;
                            if (v.duration.length() == 0) v.duration = s.duration;
                            if (v.author.length() == 0) v.author = s.author;
                        }
                        showVideo(v, g);
                        break;
                    }
                    case T_PLAY:
                        doPlay(g);
                        break;
                    case T_DOWNLOAD:
                        doDownload(g, ((Boolean) arg).booleanValue());
                        break;
                    case T_PLAYFILE:
                        playFile((String) arg);
                        break;
                    case T_UPDATE: {
                        int n = api.updateInstances(settings.listUrl);
                        settings.lastUpdate = System.currentTimeMillis();
                        settings.save(api);
                        say("Testing servers...");
                        api.sweep(false);
                        if (g != gen) return;
                        showServers();
                        break;
                    }
                    case T_SWEEP:
                        api.sweep(true);
                        if (g != gen) return;
                        showServers();
                        break;
                    case T_THUMB:
                        doThumb((Video) arg);
                        break;
                    case T_LOGFILE: {
                        String path = dir() + "tubeme-log.txt";
                        OutputStream o = Files.openOut(path);
                        try {
                            if (Log.hasPrevious()) o.write(Log.bytes("--- previous session ---\n" + Log.text(true)));
                            o.write(Log.bytes("--- this session ---\n" + Log.text(false)));
                        } finally {
                            try { o.close(); } catch (Throwable t) {}
                            Files.closeOut();
                        }
                        info("Log", "Saved to " + path.substring(8), menu);
                        break;
                    }
                }
                if (kind != T_THUMB) Log.add("task " + kind + " done");
            } catch (Throwable t) {
                Log.err("task " + kind, t);
                if (kind == T_THUMB || g != gen) return;
                alert("Error", Api.msg(t), home());
            }
        }
    }

    /** Refreshes the shared server list now and then. Never fatal. */
    private void freshServers(int g) {
        long now = System.currentTimeMillis();
        if (settings.lastUpdate != 0 && now - settings.lastUpdate < UPDATE_EVERY) return;
        try {
            api.updateInstances(settings.listUrl);
        } catch (Throwable t) {
            // keep what we have; try again next session, not on every request
        }
        settings.lastUpdate = now;
        settings.save(api);
    }

    private void doThumb(Video v) throws Exception {
        String u = Api.thumbUrl(v, display.getCurrent() == null ? 240 : display.getCurrent().getWidth());
        if (u.length() == 0) return;
        Log.add("thumb " + u + " free=" + (Runtime.getRuntime().freeMemory() / 1024) + "K");
        byte[] d = Net.get(u, 80000);
        Image img = Image.createImage(d, 0, d.length);
        Log.add("thumb ok " + d.length + "b " + img.getWidth() + "x" + img.getHeight());
        Form f = videoForm;
        if (f != null && cur == v) {
            Thumb t = new Thumb(img);
            t.setLayout(Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_AFTER);
            f.insert(0, t);
        }
    }

    private String dir() throws Exception {
        if (dirOk == null) {
            Log.add("pickDir pref=" + settings.dir);
            dirOk = Files.pickDir(settings.dir);
            Log.add("dir=" + dirOk);
            if (settings.dir.length() == 0) {
                // remember it so the next start doesn't probe (and prompt) again
                settings.dir = dirOk;
                settings.save(api);
            }
        }
        return dirOk;
    }

    private void doPlay(int g) throws Exception {
        Video v = cur;
        if (v == null) return;
        int m = mode();
        Log.add("play mode=" + m + " q=" + settings.quality + " skip=" + skip);
        codecNote();
        // Test before handing over: a player given a link it can't use fails
        // without telling us why, so try the link ourselves and move on if it's bad.
        Api.Stream s = null;
        String why = null;
        int tries = 0;
        do {
            s = api.stream(v, settings.quality, skip);
            lastNext = s.next;
            Log.add("stream " + s.url);
            if (!s.probe) break;
            say("Testing the link from " + Api.host(s.server));
            why = Net.probe(s.url);
            if (why == null) break;
            say("Link is no good: " + why);
            skip = s.next;
            tries++;
        } while (tries < api.streamServers() && g == gen);
        if (why != null) throw new Exception("No server could deliver this video (" + why + ")");
        if (g != gen) return;
        say("Opening player");
        if (m == Settings.MODE_INAPP) {
            startPlayer(s.url);
            return;
        }
        String target = s.url;
        if (m == Settings.MODE_REAL) {
            try {
                target = Files.writeRam(dir(), s.url);
                Log.add("ram written " + target);
            } catch (Throwable t) {
                Log.err("writeRam", t);
                // can't write the .ram: let the phone deal with the plain link
                target = s.url;
            }
        }
        launch(target);
    }

    private void startPlayer(String url) {
        player = PlayerCanvas.start(this, url);
        show(player);
    }

    /** Hands a URL or file to the phone. */
    private void launch(String url) throws Exception {
        Log.add("platformRequest " + url);
        boolean mustExit = platformRequest(url);
        Log.add("platformRequest returned " + mustExit);
        if (mustExit) info("Player", "Close TubeME and the video will open.", home());
        else show(home());
    }

    private void playFile(String path) throws Exception {
        Log.add("playFile " + path);
        if (!Util.isSymbian() && settings.mode != Settings.MODE_EXTERNAL && hasVideoPlayer()) startPlayer(path);
        else launch(path);
    }

    private void doDownload(int g, boolean thenPlay) throws Exception {
        Video v = cur;
        if (v == null) return;
        Log.add("download q=" + settings.quality + " skip=" + skip + " thenPlay=" + thenPlay);
        codecNote();
        say("Checking the download folder");
        String folder = dir();
        if (g != gen) return;
        HttpConnection c = null;
        Api.Stream s = null;
        Exception last = null;
        // a server can hand out a URL and still fail to deliver: walk the list
        int tries = 0;
        do {
            try {
                s = api.stream(v, settings.quality, skip);
                lastNext = s.next;
                say("Connecting to " + Api.host(s.server));
                Log.add("dl open " + s.url);
                c = Net.open(s.url);
            } catch (Exception e) {
                Log.err("dl open", e);
                if (s != null) say(Api.host(s.server) + ": " + Api.msg(e));
                last = e;
                c = null;
                if (s == null) break;
                skip = s.next;
            }
            tries++;
        } while (c == null && tries < api.streamServers() && g == gen);
        if (g != gen) {
            Net.close(c);
            return;
        }
        if (c == null) throw new Exception("Download failed: " + (last == null ? "no servers" : Api.msg(last)));

        String path = folder + "TubeME_" + v.id + "." + s.ext;
        InputStream in = null;
        OutputStream out = null;
        long done = 0;
        boolean ok = false;
        try {
            long len = c.getLength();
            Log.add("dl connected len=" + len + " type=" + c.getType() + " -> " + path);
            say(len > 0 ? "Downloading " + (len / 1024) + " KB" : "Downloading (size unknown)");
            int step = 0;
            out = Files.openOut(path);
            in = c.openInputStream();
            Log.add("dl streams open");
            byte[] buf = new byte[4096];
            int n;
            long mark = 0;
            boolean first = true;
            long logMark = 0;
            while ((n = in.read(buf)) > 0) {
                if (g != gen) return;
                if (first) {
                    first = false;
                    Log.add("dl first bytes");
                } else if (done - logMark >= 262144) {
                    logMark = done;
                    Log.add("dl " + (done / 1024) + "K");
                }
                out.write(buf, 0, n);
                done += n;
                if (done - mark >= 32768) {
                    mark = done;
                    if (len > 0) {
                        // a new line every 5%
                        int pct = (int) (done * 100 / len) / 5;
                        if (pct > step) {
                            step = pct;
                            say("Downloading " + (pct * 5) + "%");
                        }
                    } else {
                        // no size from the server: a new line every 256 KB
                        int kb = (int) (done / 262144);
                        if (kb > step) {
                            step = kb;
                            say("Downloading " + (done / 1024) + " KB");
                        }
                    }
                }
            }
            Log.add("dl done " + done + "b");
            say("Download finished");
            if (done == 0) throw new Exception("Server sent an empty video");
            ok = true;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            try { if (out != null) out.close(); } catch (Throwable t) {}
            Files.closeOut();
            Net.close(c);
            if (!ok) Files.delete(path);
        }
        if (g != gen) return;
        savedPath = path;
        if (thenPlay) {
            showSaved(path, done);
            playFile(path);
        } else {
            showSaved(path, done);
        }
    }
}
