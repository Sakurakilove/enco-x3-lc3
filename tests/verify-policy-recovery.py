#!/data/data/com.termux/files/usr/bin/python
"""Run the production policy-recovery method with a fake service/database.
This checks persisted-selection boundaries; it cannot validate radio behavior.
"""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
s = (root / 'src/local/enco/lc3/Entry.java').read_text()
a = s.index('    private static boolean restoreSelectedMainPolicy(')
b = s.index('    private static boolean realTargetGroup(', a)
method = s[a:b]
harness = r'''
import java.util.HashMap;
import java.util.Map;
public class PolicyRecoveryCheck {
    static boolean paused, manualDisconnect, rememberedLe;
    static BluetoothDevice device = new BluetoothDevice();
    static Db db = new Db();
    static Object adapter = new Object(), le = new Object();
    static BluetoothDevice base() { return device; }
    static void log(String ignored) {}
    static Object getService(ClassLoader loader, String cls, String getter) {
        return cls.contains("le_audio") ? le : adapter;
    }
    static class BluetoothDevice {
        static final int BOND_BONDED = 12;
        int bond = 12;
        int getBondState() { return bond; }
    }
    static class Db {
        Map<Integer,Integer> policies = new HashMap<>();
        int writes;
        boolean reject;
        int get(int profile) { return policies.getOrDefault(profile, -1); }
    }
    static class XposedHelpers {
        static Object callMethod(Object receiver, String method, Object... args) {
            if (receiver == adapter && method.equals("getDatabase")) return db;
            if (receiver == le && method.equals("getConnectionPolicy")) return db.get(22);
            if (receiver == db && method.equals("getProfileConnectionPolicy")) return db.get((Integer)args[1]);
            if (receiver == db && method.equals("setProfileConnectionPolicy")) {
                if (db.reject) return false;
                db.policies.put((Integer)args[1], (Integer)args[2]); db.writes++; return true;
            }
            throw new AssertionError("Unexpected call, especially a vendor policy setter: " + method);
        }
    }
__METHOD__
    static void reset() {
        paused = manualDisconnect = false; rememberedLe = true;
        device = new BluetoothDevice(); db = new Db(); adapter = new Object(); le = new Object();
    }
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        reset();
        check(restoreSelectedMainPolicy(null,"test") && db.get(22)==100 && db.get(25)==100 && db.writes==2,"rebond resets both policies");
        reset(); db.policies.put(22,0);
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"explicit LE off wins");
        reset(); db.policies.put(25,0);
        check(restoreSelectedMainPolicy(null,"test") && db.get(25)==0 && db.writes==1,"disabled CSIP preserved");
        reset(); db.policies.put(22,100);
        check(restoreSelectedMainPolicy(null,"test") && db.writes==1,"only missing CSIP recovered");
        reset(); db.policies.put(22,100); db.policies.put(25,100);
        check(restoreSelectedMainPolicy(null,"test") && db.writes==0,"already selected leaves DB untouched");
        reset(); rememberedLe=false;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"no saved selection");
        reset(); paused=true;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"root classic pause");
        reset(); manualDisconnect=true;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"manual disconnect or external unpair");
        reset(); device.bond=10;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"unbonded cannot restore");
        reset(); device.bond=11;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"pairing in progress cannot restore");
        reset(); le=null;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"LE service unavailable");
        reset(); adapter=null;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"adapter unavailable");
        reset(); db.reject=true;
        check(!restoreSelectedMainPolicy(null,"test") && db.writes==0,"database failure propagates");
        System.out.println("PASS: 13 policy-recovery cases (production method; radio not tested)");
    }
}
'''.replace('__METHOD__', method)
with tempfile.TemporaryDirectory(prefix='enco-policy-check-') as d:
    path = Path(d) / 'PolicyRecoveryCheck.java'
    path.write_text(harness)
    subprocess.run(['javac',str(path)],check=True)
    subprocess.run(['java','-cp',d,'PolicyRecoveryCheck'],check=True)
