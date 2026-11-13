package tube;

import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * JSR-75 access. Only touched after Util.hasFiles() says yes, so phones without
 * the file API never load this class.
 */
final class Files {
    private Files() {}

    private static FileConnection open(String url, int mode) throws java.io.IOException {
        synchronized (Util.OPENING) {
            return (FileConnection) Connector.open(url, mode);
        }
    }

    private static FileConnection outFc;

    /** Opens (replacing) path for writing. One file at a time; pair with closeOut(). */
    static OutputStream openOut(String path) throws Exception {
        closeOut();
        outFc = create(path);
        return outFc.openOutputStream();
    }

    static void closeOut() {
        FileConnection fc = outFc;
        outFc = null;
        try {
            if (fc != null) fc.close();
        } catch (Throwable t) {
        }
    }

    /**
     * Finds a folder we can really write to. Unsigned MIDlets get "access
     * denied" in many places, so each candidate is tested with a tiny file.
     * Returns a "file:///.../" URL, or throws.
     */
    static String pickDir(String preferred) throws Exception {
        Vector c = new Vector();
        if (preferred != null && preferred.length() > 0) c.addElement(norm(preferred));
        String[] props = { "fileconn.dir.videos", "fileconn.dir.memorycard", "fileconn.dir.photos", "fileconn.dir.private" };
        String card = System.getProperty("fileconn.dir.memorycard");
        if (card != null) c.addElement(norm(card) + "Videos/");
        for (int i = 0; i < props.length; i++) {
            String p = System.getProperty(props[i]);
            if (p != null && p.length() > 0) c.addElement(norm(p));
        }
        try {
            Enumeration e;
            synchronized (Util.OPENING) {
                e = FileSystemRegistry.listRoots();
            }
            Vector roots = new Vector();
            while (e.hasMoreElements()) roots.addElement((String) e.nextElement());
            // memory card style roots first (E:/, SDCard/, ...), C:/ last
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < roots.size(); i++) {
                    String r = (String) roots.elementAt(i);
                    boolean c0 = r.toLowerCase().startsWith("c:");
                    if (c0 == (pass == 1)) c.addElement("file:///" + r + (c0 ? "Data/" : ""));
                }
            }
        } catch (Throwable t) {
        }
        Throwable last = null;
        for (int i = 0; i < c.size(); i++) {
            String d = (String) c.elementAt(i);
            try {
                probe(d);
                return d;
            } catch (Throwable t) {
                Log.err("probe " + d, t);
                last = t;
            }
        }
        throw new Exception("No writable folder" + (last == null ? "" : " (" + Api.msg(last) + ")"));
    }

    private static String norm(String d) {
        if (!d.startsWith("file://")) d = "file:///" + (d.startsWith("/") ? d.substring(1) : d);
        return d.endsWith("/") ? d : d + "/";
    }

    private static void probe(String dir) throws Exception {
        FileConnection fc = open(dir + "tubeme.tmp", Connector.READ_WRITE);
        try {
            if (fc.exists()) fc.delete();
            fc.create();
            OutputStream o = fc.openOutputStream();
            o.write(1);
            o.close();
            fc.delete();
        } finally {
            fc.close();
        }
    }

    /** Opens (replacing) a file for writing. Caller closes both ends via close(). */
    static FileConnection create(String url) throws Exception {
        FileConnection fc = open(url, Connector.READ_WRITE);
        if (fc.exists()) fc.delete();
        fc.create();
        return fc;
    }

    /**
     * Writes a one-line .ram playlist. RealPlayer opens it and then streams
     * the URL inside, which skips the browser detour an http link would take.
     */
    static String writeRam(String dir, String streamUrl) throws Exception {
        // The player may still hold the last .ram open, so rotate through a
        // few names instead of replacing the one it is reading.
        Exception last = null;
        for (int i = 0; i < 4; i++) {
            ramNo = ramNo % 4 + 1;
            String path = dir + "tubeme" + ramNo + ".ram";
            try {
                FileConnection fc = create(path);
                try {
                    OutputStream o = fc.openOutputStream();
                    o.write(Log.bytes(streamUrl));
                    o.write('\n');
                    o.close();
                } finally {
                    fc.close();
                }
                return path;
            } catch (Exception e) {
                Log.err("ram " + path, e);
                last = e;
            }
        }
        throw last;
    }

    private static int ramNo;

    static void delete(String url) {
        try {
            FileConnection fc = open(url, Connector.READ_WRITE);
            if (fc.exists()) fc.delete();
            fc.close();
        } catch (Throwable t) {
        }
    }
}
