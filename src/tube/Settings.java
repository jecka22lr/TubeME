package tube;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;

import javax.microedition.rms.RecordStore;

/** Everything that survives a restart, kept in one RMS record. */
final class Settings {
    static final int MODE_AUTO = 0;
    /** Symbian: hand the stream to RealPlayer through a .ram file. */
    static final int MODE_REAL = 1;
    /** Save to the memory card first, then play the file. */
    static final int MODE_DOWNLOAD = 2;
    /** Play inside the app with MMAPI (JSR-135). */
    static final int MODE_INAPP = 3;
    /** Give the URL to the phone (browser or native player). */
    static final int MODE_EXTERNAL = 4;

    /**
     * Modes worth offering. Symbian gets all of them; elsewhere there is no
     * RealPlayer to stream to.
     */
    static int[] modeIds(boolean symbian) {
        if (symbian) return new int[] { MODE_AUTO, MODE_REAL, MODE_DOWNLOAD, MODE_INAPP, MODE_EXTERNAL };
        return new int[] { MODE_AUTO, MODE_DOWNLOAD, MODE_INAPP, MODE_EXTERNAL };
    }

    /** On Symbian both file routes end in RealPlayer, so the names say what differs. */
    static String[] modeNames(boolean symbian) {
        if (symbian) {
            return new String[] { "Auto", "RealPlayer, stream", "RealPlayer, download first", "In-app player", "Phone's player/browser" };
        }
        return new String[] { "Auto", "RealPlayer (Symbian)", "Download, then play", "In-app player", "Phone's player/browser" };
    }

    int mode = MODE_AUTO;
    int quality = Api.Q_MP4V;
    boolean thumbs = true;
    /** List every quality, including ones this phone is believed not to show. */
    boolean allQualities;
    String listUrl = Api.DEFAULT_LIST_URL;
    String dir = "";
    long lastUpdate;

    private static final String STORE = "tubeme";

    void load(Api api) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            if (rs.getNumRecords() < 1) return;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            int ver = in.readInt();
            if (ver < 1 || ver > 4) return;
            mode = in.readInt();
            quality = in.readInt();
            thumbs = in.readBoolean();
            listUrl = in.readUTF();
            dir = in.readUTF();
            lastUpdate = in.readLong();
            api.custom = in.readUTF();
            Vector l = readList(in), y = readList(in), i = readList(in);
            if (l.size() > 0) api.legacy = l;
            api.yt2009 = y;
            api.invidious = i;
            if (ver >= 2) {
                Vector m = readList(in);
                for (int k = 0; k < m.size(); k++) api.missing.put(m.elementAt(k), "");
            }
            if (ver >= 3) api.own = in.readUTF();
            if (ver >= 4) allQualities = in.readBoolean();
        } catch (Throwable t) {
            // first run or damaged record: defaults are fine
        } finally {
            try {
                if (rs != null) rs.closeRecordStore();
            } catch (Throwable t) {
            }
        }
    }

    void save(Api api) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(4);
            o.writeInt(mode);
            o.writeInt(quality);
            o.writeBoolean(thumbs);
            o.writeUTF(listUrl);
            o.writeUTF(dir);
            o.writeLong(lastUpdate);
            o.writeUTF(api.custom);
            writeList(o, api.legacy);
            writeList(o, api.yt2009);
            writeList(o, api.invidious);
            Vector m = new Vector();
            for (java.util.Enumeration en = api.missing.keys(); en.hasMoreElements();) m.addElement(en.nextElement());
            writeList(o, m);
            o.writeUTF(api.own);
            o.writeBoolean(allQualities);
            byte[] d = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() < 1) rs.addRecord(d, 0, d.length);
            else rs.setRecord(1, d, 0, d.length);
        } catch (Throwable t) {
        } finally {
            try {
                if (rs != null) rs.closeRecordStore();
            } catch (Throwable t) {
            }
        }
    }

    private static Vector readList(DataInputStream in) throws Exception {
        Vector v = new Vector();
        int n = in.readInt();
        for (int i = 0; i < n; i++) v.addElement(in.readUTF());
        return v;
    }

    private static void writeList(DataOutputStream o, Vector v) throws Exception {
        o.writeInt(v.size());
        for (int i = 0; i < v.size(); i++) o.writeUTF((String) v.elementAt(i));
    }
}
