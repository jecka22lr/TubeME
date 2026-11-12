package javax.microedition.io;
import java.io.*; import java.lang.reflect.Proxy; import java.net.HttpURLConnection; import java.net.URL;
/** Desktop stand-in: http over java.net (redirects NOT followed, like strict phones), files under /tmp/tubefs. */
public class Connector {
  public static final int READ=1, WRITE=2, READ_WRITE=3;
  public static Connection open(String u) throws IOException { return open(u, READ_WRITE, false); }
  public static Connection open(String u, int m) throws IOException { return open(u, m, false); }
  public static Connection open(final String u, int m, boolean t) throws IOException {
    if (u.startsWith("file://")) return javax.microedition.io.file.FileSystemRegistry.file(u);
    if (!u.startsWith("http://")) throw new ConnectionNotFoundException(u);
    final HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection(java.net.Proxy.NO_PROXY);
    h.setInstanceFollowRedirects(false); h.setConnectTimeout(3000); h.setReadTimeout(8000);
    return (Connection) Proxy.newProxyInstance(Connector.class.getClassLoader(), new Class[]{HttpConnection.class}, (p, me, a) -> {
      switch (me.getName()) {
        case "setRequestMethod": h.setRequestMethod((String) a[0]); return null;
        case "setRequestProperty": h.setRequestProperty((String) a[0], (String) a[1]); return null;
        case "getResponseCode": return h.getResponseCode();
        case "getHeaderField": return a[0] instanceof String ? h.getHeaderField((String) a[0]) : h.getHeaderField((Integer) a[0]);
        case "getLength": return h.getContentLengthLong();
        case "getType": return h.getContentType();
        case "getURL": return u;
        case "openInputStream": return h.getInputStream();
        case "close": h.disconnect(); return null;
        default: return null; } });
  }
}
