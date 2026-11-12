package tube;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VideoControl;
import javax.microedition.media.control.VolumeControl;

/**
 * In-app playback through MMAPI. Kept in its own class so phones without
 * JSR-135 video never load it.
 */
final class PlayerCanvas extends Canvas implements Runnable, PlayerListener, CommandListener {
    private final TubeMIDlet app;
    private final String url;
    private final Command back = new Command("Back", Command.BACK, 1);
    private final Command pause = new Command("Pause", "Pause / resume", Command.OK, 2);

    private Player player;
    private VideoControl video;
    private String status = "Loading...";
    private boolean closed;
    private int volume = 80;

    PlayerCanvas(TubeMIDlet app, String url) {
        this.app = app;
        this.url = url;
        addCommand(back);
        addCommand(pause);
        setCommandListener(this);
    }

    /**
     * Creates the player screen and starts loading. TubeMIDlet only ever sees
     * a Displayable, so on phones without MMAPI this class is never loaded
     * (a typed reference would make the verifier pull it in at startup).
     */
    static Displayable start(TubeMIDlet app, String url) {
        PlayerCanvas p = new PlayerCanvas(app, url);
        new Thread(p).start();
        return p;
    }

    static void stop(Displayable d) {
        if (d instanceof PlayerCanvas) ((PlayerCanvas) d).close();
    }

    /** Does this phone's MMAPI list any video type? */
    static boolean hasVideo() {
        String[] t = Manager.getSupportedContentTypes(null);
        if (t == null) return false;
        for (int i = 0; i < t.length; i++) {
            if (t[i] != null && t[i].toLowerCase().startsWith("video/")) return true;
        }
        return false;
    }

    /** Opens the stream; called on a worker thread. */
    public void run() {
        try {
            Log.add("player create " + url);
            Player p = Manager.createPlayer(url);
            player = p;
            p.addPlayerListener(this);
            Log.add("player realize");
            p.realize();
            Log.add("player realized type=" + p.getContentType());
            if (closed) { p.close(); return; }
            VideoControl vc = (VideoControl) p.getControl("VideoControl");
            if (vc != null) {
                vc.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, this);
                video = vc;
                fit();
                vc.setVisible(true);
            }
            status = "Buffering...";
            repaint();
            Log.add("player prefetch, video=" + (vc != null));
            p.prefetch();
            setVolume(0);
            if (closed) { p.close(); return; }
            p.start();
            Log.add("player started");
            status = vc == null ? "Playing (audio only)" : "";
            repaint();
        } catch (Throwable t) {
            Log.err("player", t);
            status = "Can't play: " + Api.msg(t);
            repaint();
        }
    }

    /** Scales the picture to the canvas, keeping the aspect ratio. */
    private void fit() {
        VideoControl vc = video;
        if (vc == null) return;
        try {
            int cw = getWidth(), ch = getHeight();
            int sw = vc.getSourceWidth(), sh = vc.getSourceHeight();
            if (sw <= 0 || sh <= 0) { sw = 4; sh = 3; }
            int w = cw, h = cw * sh / sw;
            if (h > ch) { h = ch; w = ch * sw / sh; }
            try {
                vc.setDisplaySize(w, h);
            } catch (Throwable t) {
                // no scaling on this phone: show at native size
                w = vc.getDisplayWidth();
                h = vc.getDisplayHeight();
            }
            vc.setDisplayLocation((cw - w) / 2, (ch - h) / 2);
        } catch (Throwable t) {
        }
    }

    protected void sizeChanged(int w, int h) {
        fit();
        repaint();
    }

    protected void paint(Graphics g) {
        g.setColor(0);
        g.fillRect(0, 0, getWidth(), getHeight());
        String s = status;
        if (s == null || s.length() == 0) return;
        g.setColor(0xFFFFFF);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        // crude wrap: split on width
        int y = 4, w = getWidth() - 8;
        while (s.length() > 0 && y < getHeight()) {
            int n = s.length();
            while (n > 1 && f.stringWidth(s.substring(0, n)) > w) n--;
            g.drawString(s.substring(0, n), 4, y, Graphics.TOP | Graphics.LEFT);
            s = s.substring(n);
            y += f.getHeight();
        }
    }

    protected void keyPressed(int key) {
        int a = 0;
        try {
            a = getGameAction(key);
        } catch (Throwable t) {
        }
        if (a == FIRE) toggle();
        else if (a == UP) setVolume(10);
        else if (a == DOWN) setVolume(-10);
        else if (a == LEFT) seek(-10);
        else if (a == RIGHT) seek(10);
    }

    private void toggle() {
        Player p = player;
        if (p == null) return;
        try {
            if (p.getState() == Player.STARTED) {
                p.stop();
                status = "Paused";
            } else {
                p.start();
                status = "";
            }
        } catch (Throwable t) {
            status = Api.msg(t);
        }
        repaint();
    }

    private void seek(int sec) {
        Player p = player;
        if (p == null) return;
        try {
            long t = p.getMediaTime() + sec * 1000000L;
            p.setMediaTime(t < 0 ? 0 : t);
        } catch (Throwable t) {
            // streams often can't seek; ignore
        }
    }

    private void setVolume(int delta) {
        Player p = player;
        if (p == null) return;
        try {
            VolumeControl v = (VolumeControl) p.getControl("VolumeControl");
            if (v == null) return;
            volume += delta;
            if (volume < 0) volume = 0;
            if (volume > 100) volume = 100;
            v.setLevel(volume);
        } catch (Throwable t) {
        }
    }

    public void playerUpdate(Player p, String event, Object data) {
        if (!"volumeChanged".equals(event)) Log.add("player event " + event + " " + data);
        if (PlayerListener.END_OF_MEDIA.equals(event)) {
            status = "Finished";
            repaint();
        } else if (PlayerListener.ERROR.equals(event)) {
            status = "Player error: " + data;
            repaint();
        }
    }

    void close() {
        closed = true;
        Player p = player;
        player = null;
        video = null;
        if (p != null) {
            try {
                p.close();
            } catch (Throwable t) {
            }
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == back) {
            close();
            app.playerClosed();
        } else if (c == pause) {
            toggle();
        }
    }
}
