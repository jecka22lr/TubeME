package tube;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
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
    private static final int T_INFO = 3, T_PLAY = 4, T_DOWNLOAD = 5,
            T_UPDATE = 6, T_THUMB = 7, T_PLAYFILE = 8, T_LOGFILE = 9, T_SWEEP = 10, T_FEED = 12, T_MORE = 13;
    private static final long UPDATE_EVERY = 3L * 24 * 3600 * 1000;

    private Display display;
    private final Api api = new Api();
    private final Settings settings = new Settings();
    private boolean started;

    private List menu;
    /** Screens the user went through (lists, video pages, comments), newest last. Back pops. */
    private final Vector nav = new Vector();
    /** List or comments Form -> its Feed; video Form -> its Video. */
    private final Hashtable feedOf = new Hashtable();
    private final Hashtable videoOf = new Hashtable();
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
    private final Command cComments = new Command("Comments", Command.SCREEN, 2);
    private final Command cRelated = new Command("Related", "Related videos", Command.SCREEN, 3);
    private final Command cChannel = new Command("Channel", "Uploader's channel", Command.SCREEN, 3);
    private final Command cMore = new Command("More", "More comments", Command.SCREEN, 2);
    private static final String MORE = "More results...";
    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cPlayFile = new Command("Play", Command.OK, 1);

    private TextBox searchBox;
    private TextBox idBox;
    private Form settingsForm;
    private ChoiceGroup sMode, sQuality, sOpts;
    private TextField sList, sCustom, sDir, sOwn;
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
        api.seed();
        // only offer qualities this phone can show; move a saved one that is no longer offered
        loadQualities();
        modeNames = Settings.modeNames(Util.isSymbian());
        modeIds = Settings.modeIds(Util.isSymbian());
        if (mIndex(settings.mode) < 0) settings.mode = Settings.MODE_AUTO;
        if (qIndex(settings.quality) < 0) settings.quality = Api.Q_MP4V_360;
        Log.add("qualities offered: " + qIds.length);
        api.progress = this;
        Log.add("TubeME " + Api.VERSION + " on " + System.getProperty("microedition.platform"));
        Log.add("symbian=" + Util.isSymbian() + " files=" + Util.hasFiles() + " mmapi=" + System.getProperty("microedition.media.version")
                + " heap=" + (Runtime.getRuntime().totalMemory() / 1024) + "K mode=" + settings.mode + " q=" + settings.quality);
        // optional JAD/manifest overrides, so servers can be changed without rebuilding
        String p = getAppProperty("TubeME-List-URL");
        if (p != null && p.trim().length() > 0 && settings.listUrl.equals(Api.DEFAULT_LIST_URL)) settings.listUrl = p.trim();
        // test hook: lets an emulator run start with list thumbnails on
        if ("1".equals(getAppProperty("TubeME-List-Thumbs"))) settings.listThumbs = true;
        p = getAppProperty("TubeME-Own-Server");
        if (p != null && p.trim().length() > 0 && api.own.length() == 0) api.own = p.trim();
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
                    // let the menu settle first, so the network prompt doesn't land mid-launch
                    Thread.sleep(1500);
                    freshServers(0);
                    api.sweep(false);
                } catch (Throwable t) {
                    Log.err("startup sweep", t);
                }
            }
        }).start();
    }

    private int[] qIds;

    /** The quality list for this phone, or the full one when the user asked for everything. */
    private void loadQualities() {
        qIds = Api.qualities(settings.allQualities ? null : System.getProperty("microedition.platform"));
    }
    private String[] modeNames;
    private int[] modeIds;

    /** Position of a mode id in the offered list, or -1. */
    private int mIndex(int id) {
        for (int i = 0; i < modeIds.length; i++) {
            if (modeIds[i] == id) return i;
        }
        return -1;
    }

    /** Quality names for a list, marking the ones no working server offers right now. */
    private String[] qNames() {
        String[] n = new String[qIds.length];
        for (int i = 0; i < qIds.length; i++) {
            n[i] = Api.Q_NAMES[qIds[i]] + (api.serversFor(qIds[i]) == 0 ? " (no server)" : "");
        }
        return n;
    }

    // what to carry on with once the user has picked another quality
    private int pendingKind;
    private Object pendingArg;
    private List pickList;
    private int[] pickIds;

    /**
     * The chosen quality turned out to be unavailable (servers differ in
     * what they offer): ask which of the available ones to use instead.
     */
    private void askQuality(String why, int kind, Object arg) {
        int n = 0;
        int[] ids = new int[qIds.length];
        for (int i = 0; i < qIds.length; i++) {
            if (qIds[i] != settings.quality && api.serversFor(qIds[i]) > 0) ids[n++] = qIds[i];
        }
        if (n == 0) {
            alert("Not available", why + ", and no other quality is either.", home());
            return;
        }
        pickIds = new int[n];
        pickList = new List("Pick another quality", Choice.IMPLICIT);
        for (int i = 0; i < n; i++) {
            pickIds[i] = ids[i];
            pickList.append(Api.Q_NAMES[ids[i]] + " (" + api.serversFor(ids[i]) + (api.serversFor(ids[i]) == 1 ? " server)" : " servers)"), null);
        }
        pickList.addCommand(cBack);
        pickList.setCommandListener(this);
        pendingKind = kind;
        pendingArg = arg;
        info("Not available", why + ". Pick another quality.", pickList);
    }

    /** Position of a quality id in the offered list, or -1. */
    private int qIndex(int id) {
        for (int i = 0; i < qIds.length; i++) {
            if (qIds[i] == id) return i;
        }
        return -1;
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
        // the running "Still waiting... N s" line updates in place instead of filling the screen
        if (waitLines.size() > 0) {
            String prev = (String) waitLines.lastElement();
            if ((line.startsWith("Still waiting") && prev.startsWith("Still waiting"))
                    || (line.startsWith("Converting ") && prev.startsWith("Converting "))) {
                waitLines.removeElementAt(waitLines.size() - 1);
            }
        }
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
        return nav.size() == 0 ? menu : (Displayable) nav.lastElement();
    }

    /** Opens a screen on top of the ones before it. */
    private void push(Displayable d) {
        nav.addElement(d);
        show(d);
    }

    /** Back: drops the top screen and returns to the one under it. */
    private void pop() {
        if (nav.size() > 0) {
            Object d = nav.lastElement();
            nav.removeElementAt(nav.size() - 1);
            feedOf.remove(d);
            videoOf.remove(d);
        }
        Displayable top = home();
        Video v = (Video) videoOf.get(top);
        if (v != null && v != cur) {
            cur = v;
            skip = 0;
            lastNext = 0;
        } else if (v == null) {
            cur = null;
        }
        show(top);
    }

    /** Starting over from the menu (a new search, Popular, a link). */
    private void resetNav() {
        gen++;
        nav.removeAllElements();
        feedOf.clear();
        videoOf.clear();
        cur = null;
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
                    resetNav();
                    run(T_FEED, new Feed(Feed.POPULAR, "", "Popular"), "Loading popular videos...");
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
                String q = searchBox.getString().trim();
                resetNav();
                run(T_FEED, new Feed(Feed.SEARCH, q, q), "Searching...");
            } else {
                show(menu);
            }
        } else if (d == idBox) {
            if (c == cOk) {
                String id = Util.videoId(idBox.getString());
                if (id == null) alert("Not a video", "That doesn't look like a video ID or link.", idBox);
                else {
                    resetNav();
                    run(T_INFO, id, "Loading video...");
                }
            } else {
                show(menu);
            }
        } else if (d instanceof List && feedOf.get(d) != null) {
            Feed f = (Feed) feedOf.get(d);
            if (c == cBack) {
                pop();
            } else {
                int i = ((List) d).getSelectedIndex();
                if (i >= 0 && i < f.items.size()) run(T_INFO, f.items.elementAt(i), "Loading video...");
                else if (i >= 0 && !f.end) run(T_MORE, new Object[] { f, d }, "Loading more...");
            }
        } else if (feedOf.get(d) != null) {
            // comments
            if (c == cMore) run(T_MORE, new Object[] { feedOf.get(d), d }, "Loading more comments...");
            else pop();
        } else if (videoOf.get(d) != null) {
            if (c == cBack) {
                gen++;
                pop();
            } else if (c == cPlay) {
                play();
            } else if (c == cDownload) {
                run(T_DOWNLOAD, Boolean.FALSE, "Preparing download...");
            } else if (c == cComments) {
                run(T_FEED, new Feed(Feed.COMMENTS, cur.id, "Comments"), "Loading comments...");
            } else if (c == cRelated) {
                run(T_FEED, new Feed(Feed.RELATED, cur.id, "Related"), "Loading related videos...");
            } else if (c == cChannel) {
                run(T_FEED, new Feed(Feed.CHANNEL, cur.channel, cur.author.length() > 0 ? cur.author : "Channel"),
                        "Loading the channel...");
            } else if (c == cOther) {
                skip = lastNext;
                play();
            } else if (c == cQuality) {
                qualityList = new List("Quality", Choice.IMPLICIT, qNames(), null);
                qualityList.setSelectedIndex(qIndex(settings.quality), true);
                qualityList.addCommand(cBack);
                qualityList.setCommandListener(this);
                show(qualityList);
            }
        } else if (d == qualityList) {
            if (c != cBack) {
                settings.quality = qIds[qualityList.getSelectedIndex()];
                settings.save(api);
                skip = 0;
            }
            show(home());
        } else if (d == pickList) {
            if (c == cBack) {
                show(home());
            } else {
                settings.quality = pickIds[pickList.getSelectedIndex()];
                settings.save(api);
                skip = 0;
                run(pendingKind, pendingArg, pendingKind == T_PLAY ? "Finding a server..." : "Preparing download...");
            }
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
        String[] mn = new String[modeIds.length];
        for (int i = 0; i < mn.length; i++) mn[i] = modeNames[modeIds[i]];
        sMode = new ChoiceGroup("Playback", Choice.POPUP, mn, null);
        sMode.setSelectedIndex(mIndex(settings.mode), true);
        sQuality = new ChoiceGroup("Quality", Choice.POPUP, qNames(), null);
        sQuality.setSelectedIndex(qIndex(settings.quality), true);
        sOpts = new ChoiceGroup(null, Choice.MULTIPLE,
                new String[] { "Thumbnail on video page", "Thumbnails in lists", "Offer every quality" }, null);
        sOpts.setSelectedIndex(0, settings.thumbs);
        sOpts.setSelectedIndex(1, settings.listThumbs);
        sOpts.setSelectedIndex(2, settings.allQualities);
        sOwn = new TextField("My TubeME server (optional)", api.own, 200, TextField.URL);
        sList = new TextField("Server list URL", settings.listUrl, 200, TextField.URL);
        sCustom = new TextField("My YtAPILegacy server (optional)", api.custom, 200, TextField.URL);
        sDir = new TextField("Download folder (empty = auto)", settings.dir, 200, TextField.ANY);
        settingsForm.append(sMode);
        settingsForm.append(sQuality);
        settingsForm.append(sOpts);
        settingsForm.append(sOwn);
        settingsForm.append(sList);
        settingsForm.append(sCustom);
        settingsForm.append(sDir);
        settingsForm.addCommand(cSave);
        settingsForm.addCommand(cBack);
        settingsForm.setCommandListener(this);
        show(settingsForm);
    }

    private void saveSettings() {
        settings.mode = modeIds[sMode.getSelectedIndex()];
        settings.quality = qIds[sQuality.getSelectedIndex()];
        settings.thumbs = sOpts.isSelected(0);
        settings.listThumbs = sOpts.isSelected(1);
        if (settings.allQualities != sOpts.isSelected(2)) {
            // the quality list changes shape: rebuild it and keep the choice if it is still offered
            settings.allQualities = sOpts.isSelected(2);
            loadQualities();
            if (qIndex(settings.quality) < 0) settings.quality = Api.Q_MP4V_360;
        }
        String l = sList.getString().trim();
        settings.listUrl = l.length() == 0 ? Api.DEFAULT_LIST_URL : l;
        api.custom = sCustom.getString().trim();
        String mine = sOwn.getString().trim();
        if (!mine.equals(api.own)) {
            api.own = mine;
            api.forgetOwn();
        }
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
        f.append(new StringItem("Auto playback", modeNames[mode()]));
        if (api.own.length() > 0) f.append(new StringItem("My server", api.own + " (" + api.status(Util.cleanBase(api.own)) + ")"));
        f.append(new StringItem("Servers", api.legacy.size() + " YtAPILegacy, " + api.yt2009.size() + " yt2009, "
                + api.invidious.size() + " Invidious"));
        if (qIds.length < Api.Q_NAMES.length) {
            f.append(new StringItem("H.264 360p", "not offered: this platform is listed as decoding H.264 only up to 320x240. "
                    + "Settings > Offer every quality brings it back"));
        }
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

    /** A list of videos with "More results..." at the end while there are more. */
    private void showFeed(Feed f) {
        if (f.comments()) {
            Form form = new Form("");
            feedOf.put(form, f);
            addComments(form, f, 0);
            form.addCommand(cBack);
            form.setCommandListener(this);
            push(form);
            return;
        }
        List l = new List(f.title, Choice.IMPLICIT);
        try {
            l.setFitPolicy(Choice.TEXT_WRAP_ON);
        } catch (Throwable t) {
        }
        feedOf.put(l, f);
        addRows(l, f, 0);
        l.addCommand(cBack);
        l.setCommandListener(this);
        push(l);
    }

    /** Adds feed entries from index from on, keeping "More results..." last. */
    private void addRows(List l, Feed f, int from) {
        if (l.size() > from) l.delete(from); // the old "More results..." row
        for (int i = from; i < f.items.size(); i++) l.append(((Video) f.items.elementAt(i)).line(), null);
        if (!f.end) l.append(MORE, null);
        if (from > 0 && from < l.size()) l.setSelectedIndex(from, true);
        if (settings.listThumbs && from < f.items.size()) new Thread(new RowIcons(l, f, from, f.items.size())).start();
    }

    private void addComments(Form form, Feed f, int from) {
        for (int i = from; i < f.items.size(); i++) {
            String[] c = (String[]) f.items.elementAt(i);
            // author as the label; likes and age on a line of their own under the text
            form.append(new StringItem(c[0].length() > 0 ? c[0] : "?", c[1] + (c[2].length() > 0 ? "\n(" + c[2] + ")" : "") + "\n"));
        }
        form.setTitle("Comments (" + f.items.size() + (f.end ? ")" : "+)"));
        if (f.end) form.removeCommand(cMore);
        else form.addCommand(cMore);
    }

    /** Loads small thumbnails into list rows one by one, as plain list icons. */
    private final class RowIcons implements Runnable {
        private final List l;
        private final Feed f;
        private final int from, to;

        RowIcons(List l, Feed f, int from, int to) {
            this.l = l;
            this.f = f;
            this.from = from;
            this.to = to;
        }

        public void run() {
            int w = l.getWidth() / 4;
            if (w > 80) w = 80;
            if (w < 32) w = 32;
            int h = w * 3 / 4;
            for (int i = from; i < to; i++) {
                if (!nav.contains(l)) return; // the list was left
                Video v = (Video) f.items.elementAt(i);
                try {
                    String u = Api.thumbUrl(v, 0);
                    if (u.length() == 0) continue;
                    byte[] d = Net.get(u, 40000, 15000);
                    Image img = Util.fit(Image.createImage(d, 0, d.length), w, h);
                    if (i < l.size() && nav.contains(l)) l.set(i, v.line(), img);
                } catch (Throwable t) {
                    Log.err("row icon " + i, t);
                }
            }
        }
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
        f.addCommand(cComments);
        f.addCommand(cRelated);
        if (v.channel.length() > 0) f.addCommand(cChannel);
        f.addCommand(cQuality);
        f.addCommand(cOther);
        f.addCommand(cBack);
        f.setCommandListener(this);
        videoOf.put(f, v);
        push(f);
        // (no heap check: totalMemory() is only the current heap on S60, not the limit;
        // a failed decode is caught and logged by the task)
        if (settings.thumbs) {
            new Thread(new Task(T_THUMB, new Object[] { v, f }, g)).start();
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
                    case T_FEED: {
                        freshServers(g);
                        Feed f = (Feed) arg;
                        api.open(f);
                        if (g != gen) return;
                        if (f.items.size() == 0) {
                            info(f.title, f.comments() ? "No comments on this video." : "Nothing found.", home());
                        } else {
                            showFeed(f);
                        }
                        break;
                    }
                    case T_MORE: {
                        Feed f = (Feed) ((Object[]) arg)[0];
                        Displayable d = (Displayable) ((Object[]) arg)[1];
                        int before = f.items.size();
                        api.more(f);
                        if (g != gen) return;
                        if (d instanceof List) addRows((List) d, f, before);
                        else addComments((Form) d, f, before);
                        if (f.items.size() == before) info(f.title, "That's all there is.", d);
                        else show(d);
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
                            if (v.channel.length() == 0) v.channel = s.channel;
                        }
                        showVideo(v, g);
                        api.warm(v, settings.quality);
                        break;
                    }
                    case T_PLAY:
                        doPlay(g, mode());
                        break;
                    case T_DOWNLOAD:
                        doDownload(g, ((Boolean) arg).booleanValue());
                        break;
                    case T_PLAYFILE:
                        playFile((String) arg);
                        break;
                    case T_UPDATE: {
                        int n = api.updateInstances(listSources());
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
                        doThumb((Video) ((Object[]) arg)[0], (Form) ((Object[]) arg)[1]);
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
                if (t instanceof Api.NoQuality && (kind == T_PLAY || kind == T_DOWNLOAD)) {
                    // remember what was learned about the servers, then let the user choose
                    settings.save(api);
                    askQuality(Api.msg(t), kind, arg);
                    return;
                }
                alert("Error", Api.msg(t), home());
            }
        }
    }

    private long listTriedAt;
    private boolean listRunning;
    private final Object listLock = new Object();

    /**
     * Refreshes the shared server list now and then. Never fatal, and never
     * makes anything wait: one attempt per session, and a search started
     * while that attempt is still running goes ahead with the list it has.
     */
    /** Where server lists come from: your own server (plain http, it fetches the rest), the list URL, TubeME's list on GitHub. */
    private Vector listSources() {
        Vector v = new Vector();
        String mine = Util.cleanBase(api.own);
        if (mine.length() > 0) v.addElement(mine + "/servers");
        v.addElement(settings.listUrl);
        if (!settings.listUrl.equals(Api.GITHUB_LIST_URL)) v.addElement(Api.GITHUB_LIST_URL);
        return v;
    }

    private void freshServers(int g) {
        long now = System.currentTimeMillis();
        // knowing only the built-in server means the list never loaded (say, no network at first start)
        boolean thin = api.yt2009.size() + api.invidious.size() == 0;
        if (!thin && settings.lastUpdate != 0 && now - settings.lastUpdate < UPDATE_EVERY) return;
        // a failed attempt is tried again after a minute, never while one is running
        synchronized (listLock) {
            if (listRunning || now - listTriedAt < 60000) return;
            listRunning = true;
            listTriedAt = now;
        }
        try {
            int n = api.updateInstances(listSources());
            settings.lastUpdate = now;
            settings.save(api);
            Log.add("server list: " + n + " servers");
        } catch (Throwable t) {
            Log.err("server list", t);
        } finally {
            synchronized (listLock) {
                listRunning = false;
            }
        }
    }

    private void doThumb(Video v, Form f) throws Exception {
        String u = Api.thumbUrl(v, display.getCurrent() == null ? 240 : display.getCurrent().getWidth());
        if (u.length() == 0) return;
        Log.add("thumb " + u + " free=" + (Runtime.getRuntime().freeMemory() / 1024) + "K");
        byte[] d = Net.get(u, 80000);
        Image img = Image.createImage(d, 0, d.length);
        Log.add("thumb ok " + d.length + "b " + img.getWidth() + "x" + img.getHeight());
        if (videoOf.get(f) == v) {
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

    private void doPlay(int g, int m) throws Exception {
        Video v = cur;
        if (v == null) return;
        Log.add("play mode=" + m + " q=" + settings.quality + " skip=" + skip);
        // Test before handing over: a player given a link it can't use fails
        // without telling us why, so try the link ourselves and move on if it's bad.
        Api.Stream s = null;
        String why = null;
        int tries = 0;
        Vector seen = new Vector();
        do {
            s = api.stream(v, settings.quality, skip);
            // the server list wraps around: stop once it hands back one already tried
            if (seen.contains(s.server)) break;
            seen.addElement(s.server);
            lastNext = s.next;
            Log.add("stream " + s.url);
            why = null;
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
        say("Checking the download folder");
        String folder;
        try {
            folder = dir();
        } catch (Exception e) {
            if (!thenPlay) throw e;
            // nowhere to save (no card, or access refused): let the phone take the link
            Log.err("no folder", e);
            say("Can't save here, handing the video to the phone instead");
            doPlay(g, Settings.MODE_EXTERNAL);
            return;
        }
        if (g != gen) return;
        HttpConnection c = null;
        Api.Stream s = null;
        Exception last = null;
        // a server can hand out a URL and still fail to deliver: walk the list
        int tries = 0;
        Vector seen = new Vector();
        while (g == gen) {
            try {
                s = api.stream(v, settings.quality, skip);
            } catch (Api.NoQuality e) {
                throw e;
            } catch (Exception e) {
                // stream() has already been through every server; asking again only repeats the waits
                last = e;
                break;
            }
            // the server list wraps around: stop once it hands back one already tried
            if (seen.contains(s.server)) break;
            seen.addElement(s.server);
            lastNext = s.next;
            try {
                say("Connecting to " + Api.host(s.server));
                Log.add("dl open " + s.url);
                // a ready file answers at once; a YtAPILegacy conversion sends nothing until it is finished
                long limit = s.probe ? 60000 : Api.convertTimeout(v);
                if (!s.probe) say("It converts the whole video first (up to " + Util.span(limit) + ")");
                c = Net.open(s.url, limit);
                String type = c.getType();
                if (type != null && (type.startsWith("text/") || type.indexOf("json") >= 0)) {
                    throw new Exception("sent " + type + " instead of video");
                }
                break;
            } catch (Exception e) {
                Net.close(c);
                c = null;
                Log.err("dl open", e);
                say(Api.host(s.server) + ": " + Api.msg(e));
                last = e;
                skip = s.next;
            }
            if (++tries >= api.streamServers()) break;
        }
        if (g != gen) {
            Net.close(c);
            return;
        }
        if (c == null) throw new Exception("Download failed: " + (last == null ? "no servers" : Api.msg(last)));

        String path = folder + "TubeME_" + v.id + "." + s.ext;
        InputStream in = null;
        OutputStream out = null;
        long done = 0;
        boolean ok = false, created = false;
        try {
            long len = c.getLength();
            Log.add("dl connected len=" + len + " type=" + c.getType() + " -> " + path);
            say(len > 0 ? "Downloading " + (len / 1024) + " KB" : "Downloading (size unknown)");
            int step = 0;
            out = Files.openOut(path);
            created = true;
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
            if (done == 0) throw new Exception("Server sent an empty video");
            // a dropped connection ends the read loop just like the end of the file does
            if (len > 0 && done < len) {
                throw new Exception("Download stopped at " + (done / 1024) + " of " + (len / 1024) + " KB");
            }
            say("Download finished");
            ok = true;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            try { if (out != null) out.close(); } catch (Throwable t) {}
            Files.closeOut();
            Net.close(c);
            // only remove what this attempt wrote, never an earlier complete download
            if (!ok && created) Files.delete(path);
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
