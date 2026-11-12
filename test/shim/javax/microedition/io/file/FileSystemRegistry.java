package javax.microedition.io.file;
import java.io.*; import java.lang.reflect.*; import java.util.*;
public class FileSystemRegistry {
  public static Enumeration listRoots() { return new Vector(Arrays.asList("E:/")).elements(); }
  public static boolean addFileSystemListener(FileSystemListener l) { return false; }
  public static boolean removeFileSystemListener(FileSystemListener l) { return false; }
  public static String ROOT = "/tmp/tubefs/";
  public static javax.microedition.io.Connection file(String url) throws IOException {
    if (url.contains("DENY")) throw new SecurityException("Access denied");
    // "NOOPEN" files act like phones that refuse a second connection per file
    if (url.contains("NOOPEN") && !url.endsWith("/")) throw new SecurityException("Access denied");
    String path = java.net.URLDecoder.decode(url.substring("file:///".length()), "UTF-8");
    final File[] cur = { new File(ROOT + path) };
    return (javax.microedition.io.Connection) Proxy.newProxyInstance(FileSystemRegistry.class.getClassLoader(), new Class[]{FileConnection.class}, (p, me, a) -> {
      File f = cur[0];
      switch (me.getName()) {
        case "exists": return f.exists();
        case "mkdir": f.mkdirs(); return null;
        case "create": f.createNewFile(); return null;
        case "fileSize": return f.length();
        case "isDirectory": return f.isDirectory();
        case "list": { Vector v = new Vector(); File[] fs = f.listFiles(); if (fs != null) { Arrays.sort(fs); for (File x : fs) v.add(x.getName() + (x.isDirectory() ? "/" : "")); } return v.elements(); }
        case "openInputStream": return new FileInputStream(f);
        case "delete": f.delete(); return null;
        case "truncate": new RandomAccessFile(f, "rw").setLength((Long) a[0]); return null;
        case "openOutputStream":
          if (a == null) return new FileOutputStream(f);
          final RandomAccessFile r = new RandomAccessFile(f, "rw"); r.seek((Long) a[0]);
          return new OutputStream() { public void write(int b) throws IOException { r.write(b); } public void write(byte[] b, int o, int l) throws IOException { r.write(b, o, l); } public void close() throws IOException { r.close(); } };
        case "rename": { File n = new File(f.getParentFile(), (String) a[0]); f.renameTo(n); cur[0] = n; return null; }
        case "setFileConnection": { String n = (String) a[0]; cur[0] = n.equals("..") ? f.getParentFile() : new File(f, n.endsWith("/") ? n.substring(0, n.length() - 1) : n); return null; }
        default: return null; } });
  }
}
