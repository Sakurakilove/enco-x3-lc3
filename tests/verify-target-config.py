#!/usr/bin/env python3
"""Exercise production target configuration with fake Android services/preferences."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
s = (root / 'src/local/enco/lc3/Entry.java').read_text()
def method(start, end):
    return s[s.index(start):s.index(end, s.index(start))]
body = method('    private static void restorePeerRecord(', '    private static void configureTarget(')
body += method('    private static void configureTarget(', '    private static void rememberLeChoice(')
body += method('    private static boolean hasUuid(', '    private static boolean targetBytes(')
harness = r'''
package local.enco.lc3;
import java.util.*;
public class TargetConfigCheck {
    static String TARGET;
    static boolean paused, rememberedLe, advertisingReady, manualDisconnect;
    static BluetoothDevice verifiedPeer;
    static long peerJoinDeadline, lastWakeup;
    static java.util.concurrent.atomic.AtomicBoolean attempted = new java.util.concurrent.atomic.AtomicBoolean();
    static Set<String> peerBondAttempts = new HashSet<>();
    static SharedPreferences store;
    static Object adapter = new Object();
    static Map<String,Integer> states = new HashMap<>();
    static void log(String ignored) {}
    static SharedPreferences prefs(Object unused) { return store; }
    static BluetoothDevice base() { return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TARGET); }
    static Object getService(ClassLoader loader, String cls, String getter) { return cls; }
    static class XposedHelpers {
        static Object callMethod(Object obj, String method, Object... args) {
            if (!method.equals("getConnectionState")) throw new AssertionError(method);
            return states.getOrDefault((String)obj,0);
        }
    }
    static class BluetoothDevice {
        static final int BOND_BONDED=12;
        final String address;
        int bond=12;
        String name;
        ParcelUuid[] uuids;
        BluetoothDevice(String address) { this.address=address; }
        int getBondState() { return bond; }
        String getAddress() { return address; }
        String getName() { return name; }
        ParcelUuid[] getUuids() { return uuids; }
    }
    static class ParcelUuid {
        final String value;
        ParcelUuid(String value) { this.value=value; }
        public String toString() { return value; }
    }
    static class BluetoothAdapter {
        static final BluetoothAdapter instance = new BluetoothAdapter();
        Map<String,BluetoothDevice> devices = new HashMap<>();
        static BluetoothAdapter getDefaultAdapter() { return instance; }
        Set<BluetoothDevice> getBondedDevices() { return new HashSet<>(devices.values()); }
        BluetoothDevice getRemoteDevice(String address) {
            return devices.computeIfAbsent(address,BluetoothDevice::new);
        }
    }
    static class SharedPreferences {
        Map<String,Object> data = new HashMap<>();
        boolean failCommit;
        String getString(String key,String fallback) { return (String)data.getOrDefault(key,fallback); }
        boolean getBoolean(String key,boolean fallback) { return (Boolean)data.getOrDefault(key,fallback); }
        Editor edit() { return new Editor(this); }
        static class Editor {
            final SharedPreferences owner;
            final Map<String,Object> pending;
            Editor(SharedPreferences owner) { this.owner=owner;pending=new HashMap<>(owner.data); }
            Editor putString(String k,String v) { pending.put(k,v);return this; }
            Editor putBoolean(String k,boolean v) { pending.put(k,v);return this; }
            Editor remove(String k) { pending.remove(k);return this; }
            boolean commit() { if(owner.failCommit)return false;owner.data=pending;return true; }
        }
    }
__PRODUCTION__
    static void reset() {
        TARGET=null;paused=rememberedLe=advertisingReady=manualDisconnect=false;
        verifiedPeer=null;peerJoinDeadline=lastWakeup=0;attempted.set(false);peerBondAttempts.clear();
        store=new SharedPreferences();states.clear();BluetoothAdapter.instance.devices.clear();
    }
    static void check(boolean ok,String msg) { if(!ok)throw new AssertionError(msg); }
    static void expectRejected(String addr,String label) {
        try { configureTarget(null,adapter,addr); throw new AssertionError(label); }
        catch (IllegalArgumentException|IllegalStateException expected) {}
    }
    public static void main(String[] args) {
        reset();configureTarget(null,adapter,"aa:bb:cc:dd:ee:12");
        check("AA:BB:CC:DD:EE:12".equals(TARGET)&&paused&&!rememberedLe,"first configuration, paused until START_LE");
        restorePeerRecord(adapter);check("AA:BB:CC:DD:EE:12".equals(TARGET),"persistent target reload");
        String before=TARGET;
        for(String bad:new String[]{"", "not-a-mac", "AA:BB:CC", "AA-BB-CC-DD-EE-FF", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "GG:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF;id"}) {
            expectRejected(bad,"invalid input accepted");check(before.equals(TARGET),"invalid input mutated target");
        }
        configureTarget(null,adapter,null);check(before.equals(TARGET),"auto changed saved target");
        reset();BluetoothAdapter.instance.getRemoteDevice("AA:BB:CC:DD:EE:12").bond=10;
        expectRejected("AA:BB:CC:DD:EE:12","unpaired target accepted");check(TARGET==null&&store.data.isEmpty(),"unpaired writes state");
        reset();BluetoothAdapter.instance.getRemoteDevice("AA:BB:CC:DD:EE:12").bond=11;
        expectRejected("AA:BB:CC:DD:EE:12","pairing target accepted");
        reset();store.failCommit=true;expectRejected("AA:BB:CC:DD:EE:12","failed persistence accepted");
        check(TARGET==null&&store.data.isEmpty(),"commit failure changes memory");
        reset();configureTarget(null,adapter,"AA:BB:CC:DD:EE:12");
        store.data.put("verified_peer","12:34:56:78:9A:BC");store.data.put("paused",false);store.data.put("le_selected",true);
        verifiedPeer=BluetoothAdapter.instance.getRemoteDevice("12:34:56:78:9A:BC");
        advertisingReady=true;attempted.set(true);lastWakeup=123;peerJoinDeadline=456;peerBondAttempts.add("peer");
        configureTarget(null,adapter,"AA:BB:CC:DD:EE:12");
        check(verifiedPeer!=null&&!paused&&rememberedLe&&advertisingReady,"same address erased peer/selection");
        states.put("com.android.bluetooth.hfp.HeadsetService",2);
        expectRejected("12:34:56:78:9A:BE","changing connected target accepted");
        check("AA:BB:CC:DD:EE:12".equals(TARGET)&&verifiedPeer!=null,"rejected change erased target/peer");
        states.clear();configureTarget(null,adapter,"12:34:56:78:9A:BE");
        check("12:34:56:78:9A:BE".equals(TARGET)&&verifiedPeer==null&&!rememberedLe&&paused,"changed target kept old peer/selection");
        check(!advertisingReady&&!attempted.get()&&lastWakeup==0&&peerJoinDeadline==0&&peerBondAttempts.isEmpty(),"changed target kept old retry state");
        check(!store.data.containsKey("verified_peer"),"old peer remains in persistent configuration");
        reset();expectRejected("auto","empty paired set accepted");
        BluetoothDevice main=BluetoothAdapter.instance.getRemoteDevice("AA:BB:CC:DD:EE:12");
        main.name="OPPO Enco X3";
        expectRejected("auto","missing classic UUID accepted");
        main.uuids=new ParcelUuid[]{new ParcelUuid("0000110b-0000-1000-8000-00805f9b34fb")};
        BluetoothDevice peer=BluetoothAdapter.instance.getRemoteDevice("12:34:56:78:9A:BC");
        peer.name="OPPO Enco X3";peer.uuids=new ParcelUuid[]{new ParcelUuid("0000184e-0000-1000-8000-00805f9b34fb")};
        configureTarget(null,adapter,"auto");check(main.address.equals(TARGET),"auto chose LE member instead of classic main");
        BluetoothDevice other=BluetoothAdapter.instance.getRemoteDevice("12:34:56:78:9A:BE");
        other.name="Enco X3";other.uuids=main.uuids;
        configureTarget(null,adapter,"auto");check(main.address.equals(TARGET),"saved target not preferred");
        TARGET=null;expectRejected("auto","multiple main devices accepted");
        other.name="Enco X3i";configureTarget(null,adapter,"auto");check(main.address.equals(TARGET),"X3i name misidentified");
        TARGET=null;main.bond=10;expectRejected("auto","unbonded auto main accepted");
        main.bond=12;main.name="renamed";expectRejected("auto","renamed unknown auto main accepted");
        main.name="  oppo ENCO X3  ";main.uuids=new ParcelUuid[]{null,new ParcelUuid("0000111e-0000-1000-8000-00805f9b34fb")};
        configureTarget(null,adapter,null);check(main.address.equals(TARGET),"case/whitespace/HFP automatic match failed");
        TARGET=null;verifiedPeer=main;expectRejected("auto","verified peer selected as main");
        check(!TargetAddress.isEncoX3Name(null)&&!TargetAddress.isEncoX3Name("Enco X4"),"unknown model matched");
        System.out.println("PASS: production target configuration, persistence failure, change guards and automatic main selection (classic UUID, ambiguity, saved choice, LE peer, name, bond)");
    }
}
'''.replace('__PRODUCTION__',body)
with tempfile.TemporaryDirectory(prefix='enco-target-check-') as d:
    src=Path(d)/'local/enco/lc3';src.mkdir(parents=True)
    (src/'TargetAddress.java').write_text((root/'src/local/enco/lc3/TargetAddress.java').read_text())
    (src/'TargetConfigCheck.java').write_text(harness)
    subprocess.run(['javac',str(src/'TargetAddress.java'),str(src/'TargetConfigCheck.java')],check=True)
    subprocess.run(['java','-cp',d,'local.enco.lc3.TargetConfigCheck'],check=True)
