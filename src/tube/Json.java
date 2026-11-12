package tube;

import java.util.Hashtable;
import java.util.Vector;

/**
 * Small JSON reader. Objects become Hashtable, arrays Vector, strings and
 * numbers String, booleans Boolean, null becomes NULL.
 */
final class Json {
    static final Object NULL = new Object();

    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws Exception {
        Json j = new Json(text);
        j.ws();
        Object o = j.value();
        return o;
    }

    /** String field of an object, "" when missing or null. */
    static String str(Object obj, String key) {
        if (!(obj instanceof Hashtable)) return "";
        Object v = ((Hashtable) obj).get(key);
        if (v == null || v == NULL) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return v.toString();
        return "";
    }

    /** Array field of an object, empty Vector when missing. */
    static Vector arr(Object obj, String key) {
        if (obj instanceof Hashtable) {
            Object v = ((Hashtable) obj).get(key);
            if (v instanceof Vector) return (Vector) v;
        }
        return new Vector();
    }

    private void ws() {
        int n = s.length();
        while (p < n) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') p++;
            else break;
        }
    }

    private Object value() throws Exception {
        if (p >= s.length()) throw new Exception("JSON: unexpected end");
        char c = s.charAt(p);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p)) { p += 4; return NULL; }
        int st = p;
        while (p < s.length()) {
            c = s.charAt(p);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') p++;
            else break;
        }
        if (p == st) throw new Exception("JSON: bad char at " + p);
        return s.substring(st, p);
    }

    private Hashtable object() throws Exception {
        Hashtable h = new Hashtable();
        p++;
        ws();
        if (p < s.length() && s.charAt(p) == '}') { p++; return h; }
        while (true) {
            ws();
            if (p >= s.length() || s.charAt(p) != '"') throw new Exception("JSON: key expected at " + p);
            String k = string();
            ws();
            if (p >= s.length() || s.charAt(p) != ':') throw new Exception("JSON: ':' expected at " + p);
            p++;
            ws();
            h.put(k, value());
            ws();
            if (p >= s.length()) throw new Exception("JSON: unexpected end");
            char c = s.charAt(p++);
            if (c == '}') return h;
            if (c != ',') throw new Exception("JSON: ',' expected at " + p);
        }
    }

    private Vector array() throws Exception {
        Vector v = new Vector();
        p++;
        ws();
        if (p < s.length() && s.charAt(p) == ']') { p++; return v; }
        while (true) {
            ws();
            v.addElement(value());
            ws();
            if (p >= s.length()) throw new Exception("JSON: unexpected end");
            char c = s.charAt(p++);
            if (c == ']') return v;
            if (c != ',') throw new Exception("JSON: ',' expected at " + p);
        }
    }

    private String string() throws Exception {
        p++;
        int n = s.length();
        int st = p;
        // fast path: no escapes
        while (p < n) {
            char c = s.charAt(p);
            if (c == '"') { return s.substring(st, p++); }
            if (c == '\\') break;
            p++;
        }
        StringBuffer b = new StringBuffer(s.substring(st, p));
        while (p < n) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (p >= n) break;
            c = s.charAt(p++);
            switch (c) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': break;
                case 'b': break;
                case 'f': break;
                case 'u':
                    if (p + 4 > n) throw new Exception("JSON: bad \\u");
                    b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default: b.append(c);
            }
        }
        throw new Exception("JSON: unterminated string");
    }
}
