package tube;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;

import javax.microedition.rms.RecordStore;

/**
 * A list of videos kept on the phone (history, favourites, saved files),
 * newest first, in one RMS record. No LCDUI, so the tests run it.
 */
final class Saved {
    private final String store;
    private final int max;
    private Vector items;

    Saved(String store, int max) {
        this.store = store;
        this.max = max;
    }

    /** Videos, newest first. */
    synchronized Vector list() {
        if (items == null) items = load();
        return items;
    }

    /** Puts v on top (moving it there if it was already in the list). */
    synchronized void add(Video v) {
        if (v == null || v.id == null || v.id.length() == 0) return;
        Vector l = list();
        remove(v.id, false);
        Video c = new Video();
        c.id = v.id;
        c.title = v.title;
        c.author = v.author;
        c.duration = v.duration;
        c.channel = v.channel;
        c.thumb = v.thumb;
        l.insertElementAt(c, 0);
        while (l.size() > max) l.removeElementAt(l.size() - 1);
        save();
    }

    synchronized boolean contains(String id) {
        return find(id) != null;
    }

    synchronized Video find(String id) {
        Vector l = list();
        for (int i = 0; i < l.size(); i++) {
            Video v = (Video) l.elementAt(i);
            if (v.id.equals(id)) return v;
        }
        return null;
    }

    synchronized void remove(String id) {
        remove(id, true);
    }

    private void remove(String id, boolean write) {
        Vector l = list();
        for (int i = l.size() - 1; i >= 0; i--) {
            if (((Video) l.elementAt(i)).id.equals(id)) l.removeElementAt(i);
        }
        if (write) save();
    }

    synchronized void clear() {
        list().removeAllElements();
        save();
    }

    private Vector load() {
        Vector v = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(store, false);
            if (rs.getNumRecords() < 1) return v;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != 1) return v;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                Video x = new Video();
                x.id = in.readUTF();
                x.title = in.readUTF();
                x.author = in.readUTF();
                x.duration = in.readUTF();
                x.channel = in.readUTF();
                x.thumb = in.readUTF();
                v.addElement(x);
            }
        } catch (Throwable t) {
            // nothing kept yet, or a damaged record: start empty
        } finally {
            try {
                if (rs != null) rs.closeRecordStore();
            } catch (Throwable t) {
            }
        }
        return v;
    }

    private void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(1);
            Vector l = items;
            o.writeInt(l.size());
            for (int i = 0; i < l.size(); i++) {
                Video x = (Video) l.elementAt(i);
                o.writeUTF(cut(x.id));
                o.writeUTF(cut(x.title));
                o.writeUTF(cut(x.author));
                o.writeUTF(cut(x.duration));
                o.writeUTF(cut(x.channel));
                o.writeUTF(cut(x.thumb));
            }
            byte[] d = bo.toByteArray();
            rs = RecordStore.openRecordStore(store, true);
            if (rs.getNumRecords() < 1) rs.addRecord(d, 0, d.length);
            else rs.setRecord(1, d, 0, d.length);
        } catch (Throwable t) {
            Log.err("saving " + store, t);
        } finally {
            try {
                if (rs != null) rs.closeRecordStore();
            } catch (Throwable t) {
            }
        }
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
