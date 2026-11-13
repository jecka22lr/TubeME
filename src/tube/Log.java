package tube;

import java.util.Vector;

import javax.microedition.rms.RecordStore;

/**
 * Trace that survives a crash: every line goes to memory and straight into
 * its own RMS store, and the next start loads it back as "previous session".
 */
final class Log {
    private static final String STORE = "tubelog";
    /** Lines kept in memory and at most this many in the store. */
    private static final int MAX = 300;

    private static final Vector now = new Vector();
    private static Vector before = new Vector();
    private static long t0 = System.currentTimeMillis();
    private static int written;

    private Log() {}

    /** Call once at startup: keeps the last session's lines, then starts a fresh store. */
    static void init() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            int n = rs.getNextRecordID();
            for (int i = 1; i < n; i++) {
                try {
                    byte[] d = rs.getRecord(i);
                    if (d != null) before.addElement(Util.utf8(d, d.length));
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
            // no previous log
        } finally {
            close(rs);
        }
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (Throwable t) {
        }
    }

    static synchronized void add(String s) {
        long ms = System.currentTimeMillis() - t0;
        String line = (ms / 1000) + "." + ((ms % 1000) / 100) + " " + s;
        now.addElement(line);
        if (now.size() > MAX) now.removeElementAt(0);
        RecordStore rs = null;
        try {
            if (written >= MAX) {
                // store is full: start it over with the newer half, so the
                // lines just before a crash are always the ones that survive
                try {
                    RecordStore.deleteRecordStore(STORE);
                } catch (Throwable t) {
                }
                rs = RecordStore.openRecordStore(STORE, true);
                written = 0;
                for (int i = now.size() / 2; i < now.size() - 1; i++) {
                    byte[] d = bytes((String) now.elementAt(i));
                    rs.addRecord(d, 0, d.length);
                    written++;
                }
            } else {
                rs = RecordStore.openRecordStore(STORE, true);
            }
            byte[] d = bytes(line);
            rs.addRecord(d, 0, d.length);
            written++;
        } catch (Throwable t) {
        } finally {
            close(rs);
        }
    }

    /** Logs where it happened plus the exception class, which phones love to hide. */
    static void err(String where, Throwable t) {
        add("ERR " + where + ": " + t.getClass().getName() + ": " + t.getMessage() + symbian(t.getMessage()));
    }

    /** Symbian reports network failures as bare numbers: say what the common ones mean. */
    static String symbian(String m) {
        if (m == null) return "";
        String code = m.trim();
        int sp = code.indexOf(' ');
        if (sp > 0) code = code.substring(0, sp);
        if (code.length() < 2 || code.charAt(0) != '-') return "";
        for (int i = 1; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') return "";
        }
        String[][] known = {
            { "-1", "not found: often no free connection left, or no access point" },
            { "-18", "network not ready" },
            { "-33", "timed out" },
            { "-34", "could not connect" },
            { "-36", "disconnected" },
            { "-46", "permission denied" },
            { "-191", "host unreachable" },
            { "-190", "network unreachable" },
            { "-5120", "name not found (DNS)" },
            { "-7370", "connection closed by the server" },
        };
        for (int i = 0; i < known.length; i++) {
            if (known[i][0].equals(code)) return " (" + known[i][1] + ")";
        }
        return "";
    }

    static String text(boolean previous) {
        Vector v = previous ? before : now;
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < v.size(); i++) b.append((String) v.elementAt(i)).append('\n');
        return b.toString();
    }

    static boolean hasPrevious() {
        return before.size() > 0;
    }

    /** Plain bytes; anything non-Latin-1 becomes '?'. Logs are ASCII anyway. */
    static byte[] bytes(String s) {
        byte[] d = new byte[s.length()];
        for (int i = 0; i < d.length; i++) {
            char c = s.charAt(i);
            d[i] = (byte) (c < 256 ? c : '?');
        }
        return d;
    }

    private static void close(RecordStore rs) {
        try {
            if (rs != null) rs.closeRecordStore();
        } catch (Throwable t) {
        }
    }
}
