package javax.microedition.rms;
import java.util.*;
/** Desktop stand-in: record stores kept in memory for the life of the test run. */
public class RecordStore {
  private static final Map<String, RecordStore> ALL = new HashMap<>();
  private final List<byte[]> recs = new ArrayList<>();
  public static synchronized RecordStore openRecordStore(String name, boolean create) throws RecordStoreException {
    RecordStore r = ALL.get(name);
    if (r == null) { if (!create) throw new RecordStoreNotFoundException(name); r = new RecordStore(); ALL.put(name, r); }
    return r;
  }
  public static synchronized void deleteRecordStore(String name) throws RecordStoreException {
    if (ALL.remove(name) == null) throw new RecordStoreNotFoundException(name);
  }
  public synchronized int getNumRecords() { int n = 0; for (byte[] b : recs) if (b != null) n++; return n; }
  public synchronized int getNextRecordID() { return recs.size() + 1; }
  public synchronized byte[] getRecord(int id) throws RecordStoreException {
    if (id < 1 || id > recs.size() || recs.get(id - 1) == null) throw new InvalidRecordIDException();
    return recs.get(id - 1).clone();
  }
  public synchronized int addRecord(byte[] d, int o, int l) { recs.add(Arrays.copyOfRange(d, o, o + l)); return recs.size(); }
  public synchronized void setRecord(int id, byte[] d, int o, int l) throws RecordStoreException {
    if (id < 1 || id > recs.size()) throw new InvalidRecordIDException();
    recs.set(id - 1, Arrays.copyOfRange(d, o, o + l));
  }
  public void closeRecordStore() {}
}
