package local.enco.lc3;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import android.os.ParcelUuid;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Entry implements IXposedHookLoadPackage {
    private static volatile String TARGET; // Root/system-only configuration; no default device.
    private static final String TAG = "EncoLc3Probe";
    private static final AtomicBoolean attempted = new AtomicBoolean();
    private static final AtomicBoolean workerBusy = new AtomicBoolean();
    private static final AtomicBoolean receiverRegistered = new AtomicBoolean();
    private static final AtomicBoolean reconnectBusy = new AtomicBoolean();
    private static volatile boolean manualDisconnect;
    private static volatile boolean rememberedLe;
    private static final AtomicInteger leChoiceGeneration = new AtomicInteger();
    private static volatile boolean paused;
    private static volatile long lastWakeup;
    private static volatile boolean advertisingReady;
    private static void log(String s) { Log.i(TAG, s); XposedBridge.log(TAG + ": " + s); }
    private static boolean target(Object o) {
        return o instanceof BluetoothDevice && TARGET != null && TARGET.equalsIgnoreCase(((BluetoothDevice)o).getAddress());
    }
    private static Object owner(Object state) throws Exception {
        for (Class<?> c = state.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals("com.android.bluetooth.hfp.HeadsetStateMachine")) {
                    f.setAccessible(true); return f.get(state);
                }
            }
        }
        throw new NoSuchFieldException("HeadsetStateMachine owner");
    }
    private static boolean requestTargetLeConnection(Object service, BluetoothDevice device) {
        try {
            if (paused || !rememberedLe || !family(device) || device.getBondState() != BluetoothDevice.BOND_BONDED) {
                log("Direct LE request skipped: target is not bonded"); return false;
            }
            if (!XposedHelpers.getBooleanField(service, "mLeAudioNativeIsInitialized")) {
                log("Direct LE request skipped: native LE Audio not initialized"); return false;
            }
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(service, "okToConnect", device))) {
                log("Direct LE request skipped: existing bond/connection policy disallows it"); return false;
            }
            if (target(device) && !advertisingReady) {
                log("Deferred target LE connect until HFP advertising command is sent");
                return false;
            }
            enableKnownGroupIfSelected(service, device);
            int state = (Integer)XposedHelpers.callMethod(service, "getConnectionState", device);
            if (state == 1 || state == 2) { log("Direct LE request skipped: current state=" + state); return true; }
            Lock lock = (Lock)XposedHelpers.getObjectField(service, "mGroupWriteLock");
            Object machine;
            lock.lock();
            try {
                Object descriptor = XposedHelpers.callMethod(service, "createDeviceDescriptor", device, false);
                if (descriptor == null) { log("Direct LE request: no device descriptor"); return false; }
                machine = XposedHelpers.callMethod(service, "getOrCreateStateMachine", device);
            } finally { lock.unlock(); }
            if (machine == null) { log("Direct LE request: no state machine"); return false; }
            Object vendor = XposedHelpers.getObjectField(service, "mLeaSvcExt");
            if (vendor != null) XposedHelpers.callMethod(vendor, "oplusHandleConnect", device);
            XposedHelpers.callMethod(machine, "sendMessage", 1);
            log("Target LE connection queued on the standard state machine; cached UUID gate bypassed only for this attempt");
            return true;
        } catch (Throwable e) { log("Direct LE request error: " + e); return false; }
    }
    private static void requestTargetCsipConnection(ClassLoader loader, BluetoothDevice device) {
        try {
            if (paused || !rememberedLe || !family(device) || device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            if (target(device) && !advertisingReady) {
                log("Deferred target CSIP connect until HFP advertising command is sent");
                return;
            }
            Class<?> cls = XposedHelpers.findClass("com.android.bluetooth.csip.CsipSetCoordinatorService", loader);
            Object service = XposedHelpers.callStaticMethod(cls, "getCsipSetCoordinatorService");
            if (service == null) { log("CSIP request skipped: service unavailable"); return; }
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(service, "okToConnect", device))) {
                log("CSIP request skipped: existing bond/connection policy disallows it"); return;
            }
            int state = (Integer)XposedHelpers.callMethod(service, "getConnectionState", device);
            if (state == 1 || state == 2) { log("CSIP already connecting/connected: " + state); return; }
            Object machines = XposedHelpers.getObjectField(service, "mStateMachines");
            synchronized (machines) {
                Object machine = XposedHelpers.callMethod(service, "getOrCreateStateMachine", device);
                if (machine == null) { log("CSIP request: no state machine"); return; }
                XposedHelpers.callMethod(machine, "sendMessage", 1);
            }
            log("Target CSIP connection queued; native discovery will determine real group membership");
        } catch (Throwable e) { log("CSIP request error: " + e); }
    }
    private static volatile BluetoothDevice verifiedPeer;
    private static volatile long peerJoinDeadline;
    private static final String PREFS = "enco_lc3_probe";
    private static final Set<String> peerBondAttempts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ThreadLocal<Boolean> insideConnect = new ThreadLocal<Boolean>();
    private static Object getService(ClassLoader loader, String cls, String getter) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(cls, loader), getter);
    }
    private static BluetoothDevice base() {
        if (TARGET == null) throw new IllegalStateException("Configure the target earbud address first");
        return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TARGET);
    }
    private static boolean family(Object d) { return target(d) || (d instanceof BluetoothDevice && d.equals(verifiedPeer)); }
    private static SharedPreferences prefs(Object adapter) {
        return ((Context)adapter).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static void restorePeerRecord(Object adapter) {
        String configured = prefs(adapter).getString("target_address", null);
        TARGET = configured == null ? null : TargetAddress.normalize(configured);
        paused = prefs(adapter).getBoolean("paused", false);
        rememberedLe = prefs(adapter).getBoolean("le_selected", false);
        String addr = prefs(adapter).getString("verified_peer", null);
        if (TARGET != null && addr != null && verifiedPeer == null) {
            try { verifiedPeer = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(addr); }
            catch (Throwable e) { log("Invalid peer record: " + e); }
        }
    }
    private static String automaticTargetAddress() {
        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        if (bluetooth == null) throw new IllegalStateException("Bluetooth adapter unavailable");
        // A saved choice wins; automatic configuration must not switch headsets silently.
        if (TARGET != null && bluetooth.getRemoteDevice(TARGET).getBondState() == BluetoothDevice.BOND_BONDED)
            return TARGET;
        Set<BluetoothDevice> bonded = bluetooth.getBondedDevices();
        BluetoothDevice candidate = null;
        if (bonded != null) for (BluetoothDevice device : bonded) {
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED
                || device.equals(verifiedPeer) || !TargetAddress.isEncoX3Name(device.getName())) continue;
            ParcelUuid[] ids = device.getUuids();
            // Independent LE members lack the main earbud's classic audio services.
            if (!hasUuid(ids, "0000110b-0000-1000-8000-00805f9b34fb")
                && !hasUuid(ids, "0000111e-0000-1000-8000-00805f9b34fb")) continue;
            if (candidate != null)
                throw new IllegalStateException("Multiple paired Enco X3 main devices; specify the target address manually");
            candidate = device;
        }
        if (candidate == null)
            throw new IllegalStateException("No unique paired Enco X3 main device with cached classic audio services; connect normally first or specify its address");
        return TargetAddress.normalize(candidate.getAddress());
    }
    private static void configureTarget(ClassLoader loader, Object adapter, String value) {
        String address = value == null || "auto".equalsIgnoreCase(value.trim())
            ? automaticTargetAddress() : TargetAddress.normalize(value);
        BluetoothDevice next = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
        if (next.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IllegalStateException("Pair the main earbud in system Bluetooth settings first");
        boolean changed = !address.equals(TARGET);
        if (changed && TARGET != null) {
            for (String[] spec : new String[][] {
                {"com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService"},
                {"com.android.bluetooth.hfp.HeadsetService", "getHeadsetService"},
                {"com.android.bluetooth.a2dp.A2dpService", "getA2dpService"}}) {
                Object service = getService(loader, spec[0], spec[1]);
                if (service != null && !Integer.valueOf(0).equals(XposedHelpers.callMethod(service, "getConnectionState", base())))
                    throw new IllegalStateException("Disconnect the previous target before changing it");
            }
        }
        SharedPreferences.Editor editor = prefs(adapter).edit().putString("target_address", address);
        if (changed) editor.remove("verified_peer").putBoolean("paused", true).putBoolean("le_selected", false);
        if (!editor.commit()) throw new IllegalStateException("Could not persist target configuration");
        if (changed) {
            verifiedPeer = null; peerJoinDeadline = 0; peerBondAttempts.clear();
            advertisingReady = false; attempted.set(false); manualDisconnect = false; lastWakeup = 0;
        }
        restorePeerRecord(adapter);
        log("Target configured; previously verified peer discarded=" + changed + "; connection pending");
    }
    private static void rememberLeChoice(ClassLoader loader, boolean selected, String source) {
        try {
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (adapter == null) return;
            if (rememberedLe != selected) leChoiceGeneration.incrementAndGet();
            rememberedLe = selected;
            prefs(adapter).edit().putBoolean("le_selected", selected).apply();
            log("Saved primary LE choice=" + selected + "; source=" + source);
        } catch (Throwable e) { log("LE choice persistence error: " + e); }
    }
    private static boolean explicitChoiceCaller(Object attribution) {
        try {
            Object pkg = XposedHelpers.callMethod(attribution, "getPackageName");
            return pkg instanceof String && !"com.android.bluetooth".equals(pkg);
        } catch (Throwable e) { return false; }
    }
    private static void recordExplicitLeChoice(ClassLoader loader, BluetoothDevice d, boolean enabled, String source) {
        try {
            if (!family(d)) return;
            // Apply accepted user intent immediately, even if preference storage later fails.
            leChoiceGeneration.incrementAndGet();
            paused = !enabled; rememberedLe = enabled; advertisingReady = false; attempted.set(false);
            if (enabled) { manualDisconnect = false; lastWakeup = 0; }
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (adapter == null || !prefs(adapter).edit().putBoolean("paused", !enabled).putBoolean("le_selected", enabled).commit())
                throw new IllegalStateException("Could not persist explicit LE choice");
            log("Accepted explicit LE choice=" + enabled + "; pending older work cancelled; source=" + source);
        } catch (Throwable e) { log("Explicit LE choice error: " + e); }
    }
    private static boolean leWorkCurrent(String expectedTarget, int generation) {
        return expectedTarget != null && expectedTarget.equals(TARGET) && rememberedLe && !paused && !manualDisconnect
            && generation == leChoiceGeneration.get();
    }
    private static boolean leWakeupStillAllowed(ClassLoader loader, BluetoothDevice d, String expectedTarget, int generation) {
        if (!leWorkCurrent(expectedTarget, generation) || !target(d) || d.getBondState() != BluetoothDevice.BOND_BONDED) return false;
        Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
        return le != null && Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", d));
    }
    private static boolean restoreSelectedMainPolicy(ClassLoader loader, String source) {
        try {
            if (paused || manualDisconnect || !rememberedLe || base().getBondState() != BluetoothDevice.BOND_BONDED) return false;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (le == null || adapter == null) return false;
            int policy = (Integer)XposedHelpers.callMethod(le, "getConnectionPolicy", base());
            if (policy == 0) return false; // An explicit disabled policy always wins over remembered intent.
            Object db = XposedHelpers.callMethod(adapter, "getDatabase");
            // Metadata can disappear during native re-pairing. Restore only UNKNOWN policies,
            // using the database so an in-progress HFP wakeup is not disabled by vendor setters.
            for (int profile : new int[] {22, 25}) {
                if (Integer.valueOf(-1).equals(XposedHelpers.callMethod(db, "getProfileConnectionPolicy", base(), profile))) {
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(db, "setProfileConnectionPolicy", base(), profile, 100))) return false;
                    log("Restored remembered primary profile=" + profile + " from UNKNOWN to ALLOWED; source=" + source);
                }
            }
            return Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()));
        } catch (Throwable e) { log("Remembered LE policy restore error: " + e); return false; }
    }
    private static boolean realTargetGroup(Object csip, int group) {
        Map<?, ?> groups = (Map<?, ?>)XposedHelpers.callMethod(csip, "getGroupUuidMapByDevice", base());
        Object uuid = groups.get(Integer.valueOf(group));
        return uuid != null && "00001853-0000-1000-8000-00805f9b34fb".equals(uuid.toString())
            && Integer.valueOf(2).equals(XposedHelpers.callMethod(csip, "getDesiredGroupSize", group));
    }
    private static void enableKnownGroupIfSelected(Object le, BluetoothDevice d) {
        try {
            if (!paused && !manualDisconnect && rememberedLe && (!target(d) || advertisingReady) && Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", d))
                && ((Integer)XposedHelpers.callMethod(le, "getGroupId", d)) >= 0) {
                XposedHelpers.callMethod(le, "setEnabledState", d, true);
                log("Re-enabled real LE group for selected device; use native background reconnection");
            }
        } catch (Throwable e) { log("Native group enable error: " + e); }
    }
    private static void preparePeer(ClassLoader loader, Object csip, BluetoothDevice peer, int group) {
        try {
            if (TARGET == null || paused || manualDisconnect || !rememberedLe || target(peer) || !realTargetGroup(csip, group)) return;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            if (le == null || Integer.valueOf(0).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) return;
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (adapter == null || base().getBondState() != BluetoothDevice.BOND_BONDED) return;
            // Address originates from native CSIS SIRK matching, never from an arbitrary scan.
            verifiedPeer = peer;
            peerJoinDeadline = SystemClock.elapsedRealtime() + 30000;
            prefs(adapter).edit().putString("verified_peer", peer.getAddress()).apply();
            int bond = peer.getBondState();
            log("Verified target's real second CSIS member; bondState=" + bond + ", group=" + group);
            if (bond == BluetoothDevice.BOND_NONE && peerBondAttempts.add(peer.getAddress())) {
                Object result = XposedHelpers.callMethod(peer, "createBond", 2);
                log("Second member standard LE pairing requested: " + result);
            } else if (bond == BluetoothDevice.BOND_BONDED) {
                connectBondedPeer(loader, peer);
            }
        } catch (Throwable e) { log("Second member preparation error: " + e); }
    }
    private static void connectBondedPeer(ClassLoader loader, BluetoothDevice peer) {
        try {
            if (paused || manualDisconnect || !rememberedLe || !family(peer) || target(peer) || peer.getBondState() != BluetoothDevice.BOND_BONDED) return;
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            if (adapter == null || le == null || base().getBondState() != BluetoothDevice.BOND_BONDED
                || Integer.valueOf(0).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) return;
            if (((Integer)XposedHelpers.callMethod(le, "getGroupId", peer)) < 0)
                peerJoinDeadline = SystemClock.elapsedRealtime() + 30000;
            Object db = XposedHelpers.callMethod(adapter, "getDatabase");
            // Only initialize unknown policy. A deliberate disabled peer is left disabled.
            for (int profile : new int[] {22, 25}) {
                int policy = (Integer)XposedHelpers.callMethod(db, "getProfileConnectionPolicy", peer, profile);
                if (policy == -1) XposedHelpers.callMethod(db, "setProfileConnectionPolicy", peer, profile, 100);
            }
            requestTargetCsipConnection(loader, peer);
            requestTargetLeConnection(le, peer);
        } catch (Throwable e) { log("Bonded second member connection error: " + e); }
    }
    private static boolean restoreNativeGroupPeer(ClassLoader loader) {
        try {
            if (TARGET == null || paused || manualDisconnect || !rememberedLe || verifiedPeer != null
                || base().getBondState() != BluetoothDevice.BOND_BONDED) return false;
            final String expectedTarget = TARGET;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
            if (le == null || csip == null
                || !Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) return false;
            int group = (Integer)XposedHelpers.callMethod(le, "getGroupId", base());
            if (group < 0 || !realTargetGroup(csip, group)) return false;
            // This is the native CSIS rank/member cache, including previously paired members.
            Object members = XposedHelpers.callMethod(csip, "getGroupDevicesOrdered", group);
            if (!(members instanceof java.util.List)) return false;
            java.util.List<?> list = (java.util.List<?>)members;
            if (list.size() != 2 || !list.contains(base())) return false;
            BluetoothDevice peer = null;
            for (Object member : list) {
                if (!(member instanceof BluetoothDevice)) return false;
                BluetoothDevice d = (BluetoothDevice)member;
                if (target(d)) continue;
                if (peer != null || d.getBondState() != BluetoothDevice.BOND_BONDED) return false;
                Map<?, ?> groups = (Map<?, ?>)XposedHelpers.callMethod(csip, "getGroupUuidMapByDevice", d);
                Object uuid = groups.get(Integer.valueOf(group));
                if (uuid == null || !"00001853-0000-1000-8000-00805f9b34fb".equals(uuid.toString())) return false;
                int leGroup = (Integer)XposedHelpers.callMethod(le, "getGroupId", d);
                if (leGroup >= 0 && leGroup != group) return false;
                peer = d;
            }
            if (peer == null || !expectedTarget.equals(TARGET) || verifiedPeer != null || paused || manualDisconnect) return false;
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (adapter == null || !prefs(adapter).edit().putString("verified_peer", peer.getAddress()).commit()) return false;
            verifiedPeer = peer;
            log("Restored already paired second member from native CSIS CAP group; group=" + group);
            return true;
        } catch (Throwable e) { log("Native cached member recovery error: " + e); return false; }
    }
    private static void processKnownCandidates(ClassLoader loader, Object csip) {
        if (restoreNativeGroupPeer(loader)) connectBondedPeer(loader, verifiedPeer);
        Map<?, ?> found = (Map<?, ?>)XposedHelpers.getObjectField(csip, "mFoundSetMemberToGroupId");
        for (Map.Entry<?, ?> entry : found.entrySet().toArray(new Map.Entry<?, ?>[0])) {
            preparePeer(loader, csip, (BluetoothDevice)entry.getKey(), (Integer)entry.getValue());
        }
    }
    private static void allowWakeupHfp(ClassLoader loader, BluetoothDevice d) {
        try {
            if (paused || manualDisconnect || !rememberedLe || !target(d) || d.getBondState() != BluetoothDevice.BOND_BONDED) return;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
            if (le == null || adapter == null || !Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", d))
                || Integer.valueOf(2).equals(XposedHelpers.callMethod(le, "getConnectionState", d))) return;
            Object db = XposedHelpers.callMethod(adapter, "getDatabase");
            if (Integer.valueOf(0).equals(XposedHelpers.callMethod(db, "getProfileConnectionPolicy", d, 1))) {
                XposedHelpers.callMethod(db, "setProfileConnectionPolicy", d, 1, 100);
                log("Restored target HFP signaling policy for LE wakeup; original admission/security checks remain");
            }
        } catch (Throwable e) { log("HFP wakeup policy error: " + e); }
    }
    private static void registerRecoveryReceiver(final ClassLoader loader, final Object adapter) {
        if (!receiverRegistered.compareAndSet(false, true)) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    try {
                        if ("local.enco.lc3.CONFIGURE_TARGET".equals(intent.getAction())) {
                            configureTarget(loader, adapter, intent.getStringExtra("address"));
                            setResultCode(1); setResultData("Target configured; use START_LE to connect");
                            return;
                        }
                        if (TARGET == null) throw new IllegalStateException("Run CONFIGURE_TARGET first");
                        if ("local.enco.lc3.RESTORE_CLASSIC".equals(intent.getAction())) {
                            paused = true;
                            rememberLeChoice(loader, false, "root classic recovery");
                            if (!prefs(adapter).edit().putBoolean("paused", true).commit())
                                throw new IllegalStateException("Could not persist pause");
                            restorePeerRecord(adapter);
                            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
                            if (le != null) {
                                if (verifiedPeer != null && verifiedPeer.getBondState() == BluetoothDevice.BOND_BONDED)
                                    XposedHelpers.callMethod(le, "setConnectionPolicy", verifiedPeer, 0);
                                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(le, "setConnectionPolicy", base(), 0)))
                                    throw new IllegalStateException("Could not disable target LE policy");
                            }
                            for (String[] service : new String[][] {
                                {"com.android.bluetooth.hfp.HeadsetService", "getHeadsetService"},
                                {"com.android.bluetooth.a2dp.A2dpService", "getA2dpService"}}) {
                                Object profile = getService(loader, service[0], service[1]);
                                if (profile == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(profile, "setConnectionPolicy", base(), 100)))
                                    throw new IllegalStateException("Classic profile policy restore failed: " + service[0]);
                            }
                            log("RESTORE_CLASSIC success: probes paused persistently, LE disabled, target HFP/A2DP allowed; bonds retained");
                            setResultCode(1); setResultData("Enco classic policies restored; module probes paused");
                        } else if ("local.enco.lc3.START_LE".equals(intent.getAction())) {
                            if (base().getBondState() != BluetoothDevice.BOND_BONDED)
                                throw new IllegalStateException("Enco X3 must remain paired");
                            if (!prefs(adapter).edit().putBoolean("paused", false).commit())
                                throw new IllegalStateException("Could not persist resume");
                            leChoiceGeneration.incrementAndGet();
                            paused = false; manualDisconnect = false; attempted.set(false); advertisingReady = false; lastWakeup = 0;
                            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
                            if (le == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(le, "setConnectionPolicy", base(), 100)))
                                throw new IllegalStateException("Could not select LE Audio");
                            rememberLeChoice(loader, true, "root START_LE");
                            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
                            if (csip != null) XposedHelpers.callMethod(csip, "setConnectionPolicy", base(), 100);
                            allowWakeupHfp(loader, base());
                            Object hs = getService(loader, "com.android.bluetooth.hfp.HeadsetService", "getHeadsetService");
                            if (hs != null && Integer.valueOf(2).equals(XposedHelpers.callMethod(hs, "getConnectionState", base())))
                                wakeupForLe(loader, base());
                            else if (hs != null) XposedHelpers.callMethod(hs, "connect", base());
                            retryExplicitLeWakeup(loader);
                            setResultCode(1); setResultData("Enco LE Audio selected; native connection pending");
                        } else if ("local.enco.lc3.REPAIR_CONTROLS".equals(intent.getAction())) {
                            if (paused || manualDisconnect || !selectedRealLe(loader, base()))
                                throw new IllegalStateException("Select and connect the paired LE group first");
                            scheduleGroupControlRecovery(loader, "root control recovery");
                            setResultCode(1); setResultData("Native group control recovery queued");
                        } else if ("local.enco.lc3.CHECK_CONTROLS".equals(intent.getAction())) {
                            boolean ready = bothGroupControlsReady(loader);
                            setResultCode(ready ? 1 : 0);
                            String details = controlReadinessDetails(loader);
                            setResultData((ready ? "READY: " : "PENDING: ") + details);
                            log("Setup readiness: " + details);
                        } else if ("local.enco.lc3.RESUME".equals(intent.getAction())) {
                            if (!prefs(adapter).edit().putBoolean("paused", false).commit())
                                throw new IllegalStateException("Could not persist resume");
                            paused = false;
                            attempted.set(false);
                            setResultCode(1); setResultData("Enco LC3 probes resumed");
                        }
                    } catch (Throwable e) {
                        log("Recovery command failed: " + e);
                        setResultCode(-1); setResultData("Enco recovery failed: " + e);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("local.enco.lc3.RESTORE_CLASSIC");
            filter.addAction("local.enco.lc3.RESUME");
            filter.addAction("local.enco.lc3.START_LE");
            filter.addAction("local.enco.lc3.CONFIGURE_TARGET");
            filter.addAction("local.enco.lc3.REPAIR_CONTROLS");
            filter.addAction("local.enco.lc3.CHECK_CONTROLS");
            XposedHelpers.callMethod(adapter, "registerReceiver", receiver, filter,
                "android.permission.BLUETOOTH_PRIVILEGED", null, 2);
            log("Root/system-only classic recovery receiver registered");
        } catch (Throwable e) { receiverRegistered.set(false); log("Recovery receiver registration failed: " + e); }
    }
    private static void installRecoveryAndPeerHooks(final ClassLoader loader) {
        try {
            final Class<?> leClass = XposedHelpers.findClass("com.android.bluetooth.le_audio.LeAudioService", loader);
            final Class<?> csipClass = XposedHelpers.findClass("com.android.bluetooth.csip.CsipSetCoordinatorService", loader);
            final Class<?> adapterClass = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterService", loader);
            XposedHelpers.findAndHookMethod("com.android.bluetooth.hfp.HeadsetService", loader,
                "okToAcceptConnection", BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (target(p.args[0])) {
                            restoreSelectedMainPolicy(loader, "incoming HFP admission");
                            allowWakeupHfp(loader, (BluetoothDevice)p.args[0]);
                        }
                    }
                });
            XposedHelpers.findAndHookMethod(adapterClass, "initProfileServices", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { restorePeerRecord(p.thisObject); registerRecoveryReceiver(loader, p.thisObject); }
                    catch (Throwable e) { log("Peer record restore error: " + e); }
                }
            });
            XposedHelpers.findAndHookMethod(adapterClass, "disconnectAllEnabledProfiles", BluetoothDevice.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (family(p.args[0])) {
                        manualDisconnect = true;
                        log("Explicit family disconnect requested; automatic retry suspended until next connect");
                    }
                }
            });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.PhonePolicy", loader, "autoConnect", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object adapter = XposedHelpers.callStaticMethod(adapterClass, "getAdapterService");
                        if (adapter == null) return;
                        restorePeerRecord(adapter);
                        Object db = XposedHelpers.callMethod(adapter, "getDatabase");
                        if (family(XposedHelpers.callMethod(db, "getMostRecentlyConnectedA2dpDevice"))
                            || family(XposedHelpers.callMethod(db, "getMostRecentlyActiveHfpDevice")))
                            scheduleMainReconnect(loader, "normal system startup auto-connect");
                    } catch (Throwable e) { log("System auto-connect observer error: " + e); }
                }
            });
            XposedHelpers.findAndHookMethod(adapterClass, "connectAllEnabledProfiles", BluetoothDevice.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        restorePeerRecord(p.thisObject);
                        BluetoothDevice d = (BluetoothDevice)p.args[0];
                        if (paused || manualDisconnect || !rememberedLe || !family(d) || d.getBondState() != BluetoothDevice.BOND_BONDED) return;
                        manualDisconnect = false;
                        restoreSelectedMainPolicy(loader, "explicit family reconnect");
                        // A persisted peer address was learned from native CSIS SIRK matching.
                        // Explicit clicks on either row enter through the paired primary device.
                        Object le = XposedHelpers.callStaticMethod(leClass, "getLeAudioService");
                        if (!target(d) && base().getBondState() == BluetoothDevice.BOND_BONDED && le != null
                            && Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) {
                            log("Explicit verified peer connect redirected to paired primary entry");
                            d = base(); p.args[0] = d;
                        }
                        if (le == null) return;
                        int policy = (Integer)XposedHelpers.callMethod(le, "getConnectionPolicy", d);
                        if (policy != 100) return;
                        if (target(d)) {
                            allowWakeupHfp(loader, d);
                            Object hs = getService(loader, "com.android.bluetooth.hfp.HeadsetService", "getHeadsetService");
                            if (hs != null && Integer.valueOf(0).equals(XposedHelpers.callMethod(hs, "getConnectionState", d)))
                                log("Classic signaling reconnect request=" + XposedHelpers.callMethod(hs, "connect", d));
                        }
                        log("Explicit reconnect request for selected LE device; repairing cached-UUID dead end");
                        requestTargetCsipConnection(loader, d);
                        requestTargetLeConnection(le, d);
                        if (target(d) && verifiedPeer != null) connectBondedPeer(loader, verifiedPeer);
                    } catch (Throwable e) { log("Explicit reconnect error: " + e); }
                }
            });
            XposedHelpers.findAndHookMethod(leClass, "connect", BluetoothDevice.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        BluetoothDevice d = (BluetoothDevice)p.args[0];
                        if (paused || !family(d) || Boolean.TRUE.equals(insideConnect.get())
                            || Integer.valueOf(0).equals(XposedHelpers.callMethod(p.thisObject, "getConnectionPolicy", d))) return;
                        Object adapter = XposedHelpers.callStaticMethod(adapterClass, "getAdapterService");
                        ParcelUuid[] ids = (ParcelUuid[])XposedHelpers.callMethod(adapter, "getRemoteUuids", d);
                        if (hasUuid(ids, "0000184e-0000-1000-8000-00805f9b34fb")) return;
                        insideConnect.set(true);
                        try {
                            requestTargetCsipConnection(loader, d);
                            p.setResult(requestTargetLeConnection(p.thisObject, d));
                        } finally { insideConnect.remove(); }
                    } catch (Throwable e) { log("Selected LE connect hook error: " + e); }
                }
            });
            XposedHelpers.findAndHookMethod(csipClass, "notifySetMemberAvailable", BluetoothDevice.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    preparePeer(loader, p.thisObject, (BluetoothDevice)p.args[0], (Integer)p.args[1]);
                    if (family(p.args[0])) scheduleGroupControlRecovery(loader, "native CSIS member available");
                }
            });
            XposedHelpers.findAndHookMethod(csipClass, "lambda$handleBondStateChanged$6", BluetoothDevice.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Integer.valueOf(12).equals(p.args[1])) connectBondedPeer(loader, (BluetoothDevice)p.args[0]);
                }
            });
            XposedHelpers.findAndHookMethod(leClass, "setConnectionPolicy", BluetoothDevice.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!family(p.args[0]) || !Boolean.TRUE.equals(p.getResult())) return;
                    if (target(p.args[0]) && (Integer.valueOf(0).equals(p.args[1]) || (!paused && Integer.valueOf(100).equals(p.args[1]))))
                        rememberLeChoice(loader, Integer.valueOf(100).equals(p.args[1]), "successful LE policy setter");
                    if (paused || !Integer.valueOf(100).equals(p.args[1])) return;
                    try {
                        enableKnownGroupIfSelected(p.thisObject, (BluetoothDevice)p.args[0]);
                        scheduleGroupControlRecovery(loader, "LE policy became allowed");
                        Object csip = XposedHelpers.callStaticMethod(csipClass, "getCsipSetCoordinatorService");
                        if (csip != null && target(p.args[0])) processKnownCandidates(loader, csip);
                    } catch (Throwable e) { log("Selected policy post-hook error: " + e); }
                }
            });
            XposedHelpers.findAndHookMethod(leClass, "notifyConnectionStateChanged", BluetoothDevice.class, int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (target(p.args[0])) {
                        if (Integer.valueOf(0).equals(p.args[1])) {
                            advertisingReady = false;
                            log("Primary LE disconnected; cleared previous advertising readiness for reconnect");
                            if (Integer.valueOf(2).equals(p.args[2])) {
                                allowWakeupHfp(loader, base());
                                scheduleMainReconnect(loader, "previously connected LE link lost");
                            }
                        } else if (Integer.valueOf(2).equals(p.args[1])) {
                            // A real successful native LE link also satisfies readiness (background reconnect).
                            advertisingReady = true;
                        }
                    }
                    if (!paused && family(p.args[0]) && Integer.valueOf(2).equals(p.args[1])) {
                        enableKnownGroupIfSelected(p.thisObject, (BluetoothDevice)p.args[0]);
                        requestVolumeConnection(loader, (BluetoothDevice)p.args[0]);
                        scheduleGroupControlRecovery(loader, "real LE member connected");
                        try {
                            Object csip = XposedHelpers.callStaticMethod(csipClass, "getCsipSetCoordinatorService");
                            if (csip != null && target(p.args[0])) processKnownCandidates(loader, csip);
                            if (target(p.args[0]) && verifiedPeer != null) connectBondedPeer(loader, verifiedPeer);
                            scheduleGroupControlRecovery(loader, "connected member group rechecked");
                        } catch (Throwable e) { log("Connected LE peer processing error: " + e); }
                    }
                }
            });
            XposedBridge.hookAllMethods(leClass, "messageFromNative", new XC_MethodHook() {
                private final Set<Integer> enabledGroups = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object event = p.args[0];
                        Object device = XposedHelpers.getObjectField(event, "device");
                        if (paused || !family(device)) return;
                        int group = (Integer)XposedHelpers.callMethod(p.thisObject, "getGroupId", device);
                        int policy = (Integer)XposedHelpers.callMethod(p.thisObject, "getConnectionPolicy", device);
                        if (group >= 0 && policy == 100 && enabledGroups.add(group)) {
                            enableKnownGroupIfSelected(p.thisObject, (BluetoothDevice)device);
                            scheduleGroupControlRecovery(loader, "native group became available");
                        }
                    } catch (Throwable e) { log("Discovered-group enable error: " + e); }
                }
            });
            log("v0.19 explicit LE reconnect and native-verified second-member pairing hooks installed");
        } catch (Throwable e) { log("Recovery/peer hooks unavailable: " + e); }
    }
    private static boolean protectRealPeerDuringJoin(ClassLoader loader, BluetoothDevice peer) {
        try {
            if (paused || target(peer) || !family(peer) || peer.getBondState() != BluetoothDevice.BOND_BONDED
                || base().getBondState() != BluetoothDevice.BOND_BONDED) return false;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
            if (le == null || csip == null || Integer.valueOf(0).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) return false;
            Map<?, ?> groups = (Map<?, ?>)XposedHelpers.callMethod(csip, "getGroupUuidMapByDevice", base());
            for (Object key : groups.keySet()) {
                int group = (Integer)key;
                if (!realTargetGroup(csip, group)) continue;
                java.util.List<?> members = (java.util.List<?>)XposedHelpers.callMethod(csip, "getGroupDevicesOrdered", group);
                if (members.contains(peer)) return true;
                // SIRK-matched candidate is allowed a bounded window for native CSIS registration.
                if (SystemClock.elapsedRealtime() < peerJoinDeadline) return true;
            }
        } catch (Throwable e) { log("Peer join guard error: " + e); }
        return false;
    }
    private static void installPeerCleanupGuard(final ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(BluetoothDevice.class, "removeBond", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    BluetoothDevice peer = (BluetoothDevice)p.thisObject;
                    if (!family(peer)) return;
                    String origin = null;
                    StringBuilder trace = new StringBuilder();
                    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                        String cls = frame.getClassName(), method = frame.getMethodName();
                        if (cls.startsWith("com.android.bluetooth.") || cls.startsWith("com.oplus.bluetooth.")) {
                            if (trace.length() < 1600) trace.append(cls).append('.').append(method).append(" <- ");
                        }
                        if ((cls.equals("com.android.bluetooth.btservice.AdapterService") && method.equals("sendUuidsInternal"))
                            || (cls.equals("com.oplus.bluetooth.leaudio.OplusLeAudioUtils") && method.equals("checkAndRemoveInvaildGroupNumber"))
                            || (cls.equals("com.oplus.bluetooth.leaudio.OplusLeAudioRemoteDevice") && method.equals("oplusTriggerRemoveBondWithNotUuid")))
                            origin = cls + "." + method;
                    }
                    log((target(peer) ? "Primary" : "Real second member") + " removeBond caller: " + trace);
                    if (target(peer)) return; // Observe only: no primary pairing/security removal is blocked.
                    // Only these cache/group heuristics are guarded; user unpairing and security failure paths remain normal.
                    if (origin != null && protectRealPeerDuringJoin(loader, peer)) {
                        log("Deferred automatic peer cleanup from " + origin + "; allowing real CSIS discovery to complete");
                        p.setResult(false);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.BondStateMachine", loader,
                "sendIntent", BluetoothDevice.class, int.class, int.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!family(p.args[0])) return;
                        log((target(p.args[0]) ? "Primary" : "Verified peer") + " bond notification state=" + p.args[1] + ", reason=" + p.args[2]);
                        if (Integer.valueOf(10).equals(p.args[1])) {
                            if (target(p.args[0])) { advertisingReady = false; attempted.set(false); }
                            else peerBondAttempts.remove(((BluetoothDevice)p.args[0]).getAddress());
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (target(p.args[0]) && Integer.valueOf(12).equals(p.args[1]) && !paused && rememberedLe)
                            scheduleMainReconnect(loader, "native primary re-pair completed");
                    }
                });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.AdapterService$AdapterServiceBinder", loader,
                "removeBond", BluetoothDevice.class, XposedHelpers.findClass("android.content.AttributionSource", loader), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (target(p.args[0])) p.setObjectExtra("externalUnpair", Boolean.valueOf(android.os.Binder.getCallingUid() != 1002));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (target(p.args[0]) && Boolean.TRUE.equals(p.getResult()) && Boolean.TRUE.equals(p.getObjectExtra("externalUnpair"))) {
                            manualDisconnect = true;
                            advertisingReady = false;
                            rememberLeChoice(loader, false, "accepted external primary unpair");
                        }
                    }
                });
            log("v0.19 real second-member cleanup guard and bond observers installed");
        } catch (Throwable e) { log("Peer cleanup guard unavailable: " + e); }
    }
    private static void scheduleMainReconnect(final ClassLoader loader, final String reason) {
        if (TARGET == null || paused || manualDisconnect || !rememberedLe || !reconnectBusy.compareAndSet(false, true)) return;
        final String scheduledTarget = TARGET;
        final int generation = leChoiceGeneration.get();
        new Thread(new Runnable() {
            public void run() {
                try {
                    log("Bounded target automatic reconnect scheduled: " + reason);
                    // Cover a short case-in/case-out cycle without scanning or continuous paging.
                    for (int attempt = 0; attempt < 4; attempt++) {
                        Thread.sleep(attempt == 0 ? 4000 : 15000);
                        if (!leWorkCurrent(scheduledTarget, generation)) return;
                        if (base().getBondState() != BluetoothDevice.BOND_BONDED) continue;
                        restoreSelectedMainPolicy(loader, "bounded automatic reconnect");
                        Object adapter = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
                        Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
                        if (adapter == null || le == null || !Integer.valueOf(12).equals(XposedHelpers.callMethod(adapter, "getState"))
                            || !Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(le, "okToConnect", base()))) return;
                        if (Integer.valueOf(2).equals(XposedHelpers.callMethod(le, "getConnectionState", base()))) return;
                        allowWakeupHfp(loader, base());
                        Object hs = getService(loader, "com.android.bluetooth.hfp.HeadsetService", "getHeadsetService");
                        if (hs == null) return;
                        int state = (Integer)XposedHelpers.callMethod(hs, "getConnectionState", base());
                        if (state == 2) {
                            wakeupForLe(loader, base());
                        } else if (state == 0) {
                            log("Automatic primary HFP signaling connect attempt=" + (attempt + 1)
                                + ", queued=" + XposedHelpers.callMethod(hs, "connect", base()));
                        }
                    }
                    log("Bounded automatic reconnect window ended; ordinary incoming connections remain available");
                } catch (Throwable e) { log("Automatic reconnect worker error: " + e); }
                finally { reconnectBusy.set(false); }
            }
        }, "EncoLc3Probe-reconnect").start();
    }
    private static void wakeupForLe(final ClassLoader loader, final BluetoothDevice d) {
        try {
                        if (paused || manualDisconnect || !rememberedLe || !target(d)) return;
                        restoreSelectedMainPolicy(loader, "HFP connected");
                        final Object selectedLe = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
                        if (selectedLe == null) return;
                        int policy = (Integer)XposedHelpers.callMethod(selectedLe, "getConnectionPolicy", d);
                        if (policy == 0 || Integer.valueOf(2).equals(XposedHelpers.callMethod(selectedLe, "getConnectionState", d))) return;
                        if (policy != 100 && !attempted.compareAndSet(false, true)) return;
                        if (SystemClock.elapsedRealtime() - lastWakeup < 15000 || !workerBusy.compareAndSet(false, true)) return;
                        final String scheduledTarget = TARGET;
                        final int generation = leChoiceGeneration.get();
                        lastWakeup = SystemClock.elapsedRealtime();
                        log("Target HFP connected; waking LE advertising for this reconnect");
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    log("Worker started; waiting 3 seconds for HFP stabilization");
                                    Thread.sleep(3000);
                                    if (!leWakeupStillAllowed(loader, d, scheduledTarget, generation)) {
                                        log("Cancelled stale LE wakeup after HFP wait; current explicit choice wins"); return;
                                    }
                                    log("Worker checking HeadsetService");
                                    Object service = getService(loader, "com.android.bluetooth.hfp.HeadsetService", "getHeadsetService");
                                    if (service == null || !Integer.valueOf(2).equals(XposedHelpers.callMethod(service, "getConnectionState", d))) {
                                        log("Target disconnected before attempt; no command sent"); return;
                                    }
                                    Object as = getService(loader, "com.android.bluetooth.btservice.AdapterService", "getAdapterService");
                                    if (as == null) { log("AdapterService unavailable"); return; }
                                    log("Calling AdapterService.setLeAudioStatus(target, 2): request LE advertising without changing saved status");
                                    XC_MethodHook.Unhook observer = null;
                                    final AtomicBoolean observed = new AtomicBoolean();
                                    try {
                                        observer = XposedHelpers.findAndHookMethod(
                                            "com.android.bluetooth.hfp.HeadsetNativeInterface", loader,
                                            "atResponseString", BluetoothDevice.class, String.class, new XC_MethodHook() {
                                                @Override protected void afterHookedMethod(MethodHookParam call) {
                                                    if (target(call.args[0]) && String.valueOf(call.args[1]).startsWith("+MTK=")) {
                                                        observed.set(Boolean.TRUE.equals(call.getResult()));
                                                        log("Native LE-advertising AT command=" + call.args[1]
                                                            + " sendResult=" + call.getResult() + "; peer acceptance requires discovery evidence");
                                                    }
                                                }
                                            });
                                    } catch (Throwable e) { log("Native AT observer unavailable: " + e); }
                                    try {
                                        if (!leWakeupStillAllowed(loader, d, scheduledTarget, generation)) return;
                                        XposedHelpers.callMethod(as, "setLeAudioStatus", d, 2);
                                    }
                                    finally { if (observer != null) observer.unhook(); }
                                    log("LE-advertising request returned; native AT observed=" + observed.get());
                                    if (!observed.get()) {
                                        log("No confirmed advertising command; keeping classic signaling for retry");
                                        return;
                                    }
                                    Thread.sleep(1500);
                                    if (!leWakeupStillAllowed(loader, d, scheduledTarget, generation)) {
                                        log("Cancelled stale LE connection after advertising wait; current explicit choice wins"); return;
                                    }
                                    advertisingReady = true;
                                    Class<?> lea = XposedHelpers.findClass("com.android.bluetooth.le_audio.LeAudioService", loader);
                                    Object leService = XposedHelpers.callStaticMethod(lea, "getLeAudioService");
                                    if (leService == null) { log("Direct LE request skipped: LE service unavailable"); return; }
                                    requestTargetCsipConnection(loader, d);
                                    requestTargetLeConnection(leService, d);
                                } catch (Throwable e) { log("Mode-switch error: " + e); }
                                finally { workerBusy.set(false); }
                            }
                        }, "EncoLc3Probe-worker").start();
        } catch (Throwable e) { log("LE wakeup scheduling error: " + e); }
    }
    private static String bluetoothOrigin() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            if ((f.getClassName().startsWith("com.android.bluetooth.") || f.getClassName().startsWith("com.oplus.bluetooth.")) && out.length() < 1800)
                out.append(f.getClassName()).append('.').append(f.getMethodName()).append(" <- ");
        }
        return out.toString();
    }
    private static boolean selectedRealLe(ClassLoader loader, BluetoothDevice d) {
        if (paused || manualDisconnect || !rememberedLe || !family(d) || d.getBondState() != BluetoothDevice.BOND_BONDED) return false;
        Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
        Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
        if (le == null || csip == null || !Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()))) return false;
        int group = (Integer)XposedHelpers.callMethod(le, "getGroupId", base());
        return group >= 0 && realTargetGroup(csip, group);
    }
    private static boolean requestVolumeConnection(ClassLoader loader, BluetoothDevice d) {
        try {
            if (!selectedRealLe(loader, d)) return false;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            if (!Integer.valueOf(2).equals(XposedHelpers.callMethod(le, "getConnectionState", d))) return false;
            Object vc = getService(loader, "com.android.bluetooth.vc.VolumeControlService", "getVolumeControlService");
            if (vc == null || !XposedHelpers.getBooleanField(vc, "mStarted")
                || XposedHelpers.getObjectField(vc, "mVolumeControlNativeInterface") == null
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(vc, "okToConnect", d))) return false;
            // Native GATT discovery must find genuine VCS; no UUID or connection result is synthesized.
            Object machines = XposedHelpers.getObjectField(vc, "mStateMachines");
            synchronized (machines) {
                int state = (Integer)XposedHelpers.callMethod(vc, "getConnectionState", d);
                if (state == 1 || state == 2) return true;
                if (state != 0) return false;
                Object machine = XposedHelpers.callMethod(vc, "getOrCreateStateMachine", d);
                if (machine == null) return false;
                XposedHelpers.callMethod(machine, "sendMessage", 1);
            }
            log("Real LE member VCP connection queued; native discovery must verify Volume Control service");
            return true;
        } catch (Throwable e) { log("VCP connection error: " + e); return false; }
    }
    private static final Set<String> controlRecoveries = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static boolean repairMemberControls(ClassLoader loader, Object le, Object csip, Object vc, BluetoothDevice d) {
        boolean ready = true;
        try {
            if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(csip, "getConnectionPolicy", d))) {
                int state = (Integer)XposedHelpers.callMethod(csip, "getConnectionState", d);
                if (state != 2) { ready = false; if (state == 0) requestTargetCsipConnection(loader, d); }
            }
        } catch (Throwable e) { ready = false; log("CSIP control recovery error: " + e); }
        try {
            if (vc == null) ready = false;
            else if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(vc, "getConnectionPolicy", d))
                && !Integer.valueOf(2).equals(XposedHelpers.callMethod(vc, "getConnectionState", d))) {
                ready = false; requestVolumeConnection(loader, d);
            }
        } catch (Throwable e) { ready = false; log("VCP control recovery error: " + e); }
        try {
            if (hasUuid(d.getUuids(), "0000180f-0000-1000-8000-00805f9b34fb")) {
                Object battery = getService(loader, "com.android.bluetooth.bas.BatteryService", "getBatteryService");
                if (battery == null) ready = false;
                else if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(battery, "getConnectionPolicy", d))) {
                    int state = (Integer)XposedHelpers.callMethod(battery, "getConnectionState", d);
                    if (state != 2) { ready = false; if (state == 0) XposedHelpers.callMethod(battery, "connectIfPossible", d); }
                }
            }
        } catch (Throwable e) { ready = false; log("Battery control recovery error: " + e); }
        // Native getters resolve UNKNOWN via normal CSIP/LE policy, preserving DENIED.
        for (String getter : new String[] {"getMcpService", "getTbsService"}) {
            try {
                Object server = XposedHelpers.callMethod(le, getter);
                if (server != null && Integer.valueOf(0).equals(XposedHelpers.callMethod(server, "getDeviceAuthorization", d)))
                    ready = false;
            } catch (Throwable e) { ready = false; log("Media/call control recovery error: " + e); }
        }
        return ready;
    }
    private static boolean repairConnectedGroupControls(ClassLoader loader) {
        try {
            if (TARGET == null || paused || manualDisconnect) return false;
            restoreNativeGroupPeer(loader);
            if (!selectedRealLe(loader, base())) return false;
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
            Object vc = getService(loader, "com.android.bluetooth.vc.VolumeControlService", "getVolumeControlService");
            if (le == null || csip == null) return false;
            int group = (Integer)XposedHelpers.callMethod(le, "getGroupId", base());
            boolean connected = false, ready = true;
            for (BluetoothDevice d : new BluetoothDevice[] {base(), verifiedPeer}) {
                if (d == null || !family(d) || d.getBondState() != BluetoothDevice.BOND_BONDED
                    || !Integer.valueOf(2).equals(XposedHelpers.callMethod(le, "getConnectionState", d))) continue;
                if (!Integer.valueOf(group).equals(XposedHelpers.callMethod(le, "getGroupId", d))) {
                    ready = false; continue; // Never repair an old peer in another group.
                }
                connected = true;
                if (!repairMemberControls(loader, le, csip, vc, d)) ready = false;
            }
            return connected && ready;
        } catch (Throwable e) { log("Group control recovery error: " + e); return false; }
    }
    private static boolean bothGroupControlsReady(ClassLoader loader) {
        try {
            restoreNativeGroupPeer(loader);
            if (TARGET == null || paused || manualDisconnect || verifiedPeer == null
                || !selectedRealLe(loader, base())) return false;
            // Keep repairing while waiting; a lone connected member is never stereo success.
            boolean controlsReady = repairConnectedGroupControls(loader);
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
            Object vc = getService(loader, "com.android.bluetooth.vc.VolumeControlService", "getVolumeControlService");
            if (le == null || csip == null || vc == null) return false;
            int group = (Integer)XposedHelpers.callMethod(le, "getGroupId", base());
            for (BluetoothDevice d : new BluetoothDevice[] {base(), verifiedPeer}) {
                if (d.getBondState() != BluetoothDevice.BOND_BONDED
                    || !Integer.valueOf(group).equals(XposedHelpers.callMethod(le, "getGroupId", d))
                    || !Integer.valueOf(2).equals(XposedHelpers.callMethod(le, "getConnectionState", d))
                    || !Integer.valueOf(2).equals(XposedHelpers.callMethod(csip, "getConnectionState", d))
                    || !Integer.valueOf(2).equals(XposedHelpers.callMethod(vc, "getConnectionState", d))) return false;
            }
            return controlsReady;
        } catch (Throwable e) { log("Two-member control readiness error: " + e); return false; }
    }
    private static void scheduleGroupControlRecovery(final ClassLoader loader, final String source) {
        final String scheduledTarget = TARGET;
        final int generation = leChoiceGeneration.get();
        if (scheduledTarget == null || paused || manualDisconnect || !rememberedLe || !controlRecoveries.add(scheduledTarget)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 22 seconds total: wait for policy/group discovery as well as LE connection.
                    for (int delay : new int[] {0, 1500, 2500, 4000, 6000, 8000}) {
                        if (delay != 0) Thread.sleep(delay);
                        if (!leWorkCurrent(scheduledTarget, generation)) return;
                        if (repairConnectedGroupControls(loader)) {
                            log("Connected native group control services ready; source=" + source); return;
                        }
                    }
                    log("Bounded group control recovery ended; collect VCP/CSIP/Battery and media/call state if incomplete");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { controlRecoveries.remove(scheduledTarget); }
            }
        }, "EncoGroupControls").start();
    }
    private static String connectionLabel(Object value) {
        if (Integer.valueOf(2).equals(value)) return "已连";
        if (Integer.valueOf(1).equals(value)) return "连接中";
        if (Integer.valueOf(3).equals(value)) return "断开中";
        if (Integer.valueOf(0).equals(value)) return "未连";
        return "未知";
    }
    private static String controlReadinessDetails(ClassLoader loader) {
        if (TARGET == null) return "主地址尚未配置";
        if (paused || manualDisconnect) return "恢复已暂停或手动断开";
        try {
            Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
            Object csip = getService(loader, "com.android.bluetooth.csip.CsipSetCoordinatorService", "getCsipSetCoordinatorService");
            Object vc = getService(loader, "com.android.bluetooth.vc.VolumeControlService", "getVolumeControlService");
            StringBuilder out = new StringBuilder();
            BluetoothDevice[] devices = new BluetoothDevice[] {base(), verifiedPeer};
            for (int i = 0; i < devices.length; i++) {
                if (i != 0) out.append("; ");
                out.append(i == 0 ? "主耳[" : "另一耳[");
                BluetoothDevice d = devices[i];
                if (d == null) { out.append("尚未由原生 CSIS 确认]"); continue; }
                out.append("配对=").append(d.getBondState()).append(", ");
                Object[] profiles = new Object[] {le, csip, vc};
                String[] labels = new String[] {"LE", "CSIP", "VCP"};
                for (int j = 0; j < profiles.length; j++) {
                    out.append(labels[j]).append('=');
                    if (profiles[j] == null) out.append("服务不可用");
                    else {
                        out.append(connectionLabel(XposedHelpers.callMethod(profiles[j], "getConnectionState", d)));
                        out.append("/策略").append(XposedHelpers.callMethod(profiles[j], "getConnectionPolicy", d));
                    }
                    out.append(", ");
                }
                if (le != null) out.append("组=").append(XposedHelpers.callMethod(le, "getGroupId", d)).append(", ");
                if (hasUuid(d.getUuids(), "0000180f-0000-1000-8000-00805f9b34fb")) {
                    Object battery = getService(loader, "com.android.bluetooth.bas.BatteryService", "getBatteryService");
                    out.append("BAS=").append(battery == null ? "服务不可用" : connectionLabel(XposedHelpers.callMethod(battery, "getConnectionState", d)));
                } else out.append("BAS=未声明");
                if (le != null) {
                    for (String getter : new String[] {"getMcpService", "getTbsService"}) {
                        Object server = XposedHelpers.callMethod(le, getter);
                        out.append(", ").append(getter.equals("getMcpService") ? "媒体授权=" : "通话授权=");
                        out.append(server == null ? "服务不可用" : XposedHelpers.callMethod(server, "getDeviceAuthorization", d));
                    }
                }
                out.append(']');
            }
            return out.toString();
        } catch (Throwable e) { return "状态查询错误: " + e; }
    }
    private static void retryExplicitLeWakeup(final ClassLoader loader) {
        final String scheduledTarget = TARGET;
        final int generation = leChoiceGeneration.get();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Let a cancelled older HFP worker retire before a rapid off/on retry.
                    Thread.sleep(3500);
                    if (leWorkCurrent(scheduledTarget, generation)) wakeupForLe(loader, base());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "EncoExplicitLeWakeup").start();
    }
    private static void afterExplicitLeChoice(ClassLoader loader, BluetoothDevice d, boolean enabled, String source) {
        recordExplicitLeChoice(loader, d, enabled, source);
        if (!enabled || paused || !rememberedLe || !family(d)) return;
        try {
            restoreSelectedMainPolicy(loader, "explicit system LE enable");
            allowWakeupHfp(loader, base());
            Object hs = getService(loader, "com.android.bluetooth.hfp.HeadsetService", "getHeadsetService");
            if (hs != null && Integer.valueOf(2).equals(XposedHelpers.callMethod(hs, "getConnectionState", base())))
                wakeupForLe(loader, base());
            else if (hs != null && Integer.valueOf(0).equals(XposedHelpers.callMethod(hs, "getConnectionState", base())))
                XposedHelpers.callMethod(hs, "connect", base());
            retryExplicitLeWakeup(loader);
            scheduleGroupControlRecovery(loader, "explicit system LE enable");
        } catch (Throwable e) { log("Explicit LE enable follow-up error: " + e); }
    }
    private static void installExplicitChoiceHooks(final ClassLoader loader) {
        try {
            Class<?> attribution = XposedHelpers.findClass("android.content.AttributionSource", loader);
            XposedHelpers.findAndHookMethod("com.android.bluetooth.le_audio.LeAudioService$BluetoothLeAudioBinder", loader,
                "setConnectionPolicy", BluetoothDevice.class, int.class, attribution, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!Boolean.TRUE.equals(p.getResult()) || !family(p.args[0]) || !explicitChoiceCaller(p.args[2])) return;
                        int policy = (Integer)p.args[1];
                        if (policy == 0 || policy == 100)
                            afterExplicitLeChoice(loader, (BluetoothDevice)p.args[0], policy == 100, "accepted external LE policy");
                    }
                });
            log("External LE policy choice observer installed");
        } catch (Throwable e) { log("External LE policy observer unavailable: " + e); }
        try {
            Class<?> attribution = XposedHelpers.findClass("android.content.AttributionSource", loader);
            Class<?> adapter = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterService", loader);
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.OplusLeAdapterService", loader,
                "setLeAudioStatus", BluetoothDevice.class, int.class, attribution, adapter, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!Boolean.TRUE.equals(p.getResult()) || !family(p.args[0]) || !explicitChoiceCaller(p.args[2])) return;
                        int status = (Integer)p.args[1];
                        if (status == 0 || status == 1)
                            afterExplicitLeChoice(loader, (BluetoothDevice)p.args[0], status == 1, "accepted external vendor LE status");
                    }
                });
            log("External vendor LE choice observer installed; advertising status 2 is not a user choice");
        } catch (Throwable e) { log("External vendor LE choice observer unavailable: " + e); }
    }
    private static void installSelectedLeAndVolumeHooks(final ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.bluetooth.leaudio.OplusLeAudioUtils", loader,
                "oplusDisableProfiles", String.class, BluetoothDevice.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            BluetoothDevice d = (BluetoothDevice)p.args[1];
                            if (!"LEA".equals(p.args[0]) || paused || manualDisconnect || !family(d)) return;
                            boolean selected = selectedRealLe(loader, d);
                            if (!selected && rememberedLe && verifiedPeer != null && base().getBondState() == BluetoothDevice.BOND_BONDED) {
                                Object le = getService(loader, "com.android.bluetooth.le_audio.LeAudioService", "getLeAudioService");
                                selected = le != null && Integer.valueOf(100).equals(XposedHelpers.callMethod(le, "getConnectionPolicy", base()));
                            }
                            if (!selected) return;
                            String origin = bluetoothOrigin();
                            // Guard only automatic classic activation. Explicit LE off and root recovery remain usable.
                            if (origin.contains("com.android.bluetooth.btservice.ActiveDeviceManager.")) {
                                log("Preserved selected Enco LE profiles during classic activation: " + origin);
                                p.setResult(null);
                            }
                        } catch (Throwable e) { log("Selected LE activation guard error: " + e); }
                    }
                });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.le_audio.LeAudioService", loader,
                "setConnectionPolicy", BluetoothDevice.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (family(p.args[0])) log("Family LE policy requested=" + p.args[1] + "; origin=" + bluetoothOrigin());
                    }
                });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.vc.VolumeControlService", loader,
                "connect", BluetoothDevice.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            BluetoothDevice d = (BluetoothDevice)p.args[0];
                            if (!family(d) || hasUuid(d.getUuids(), "00001844-0000-1000-8000-00805f9b34fb")) return;
                            if (requestVolumeConnection(loader, d)) p.setResult(true);
                        } catch (Throwable e) { log("VCP cached service gate hook error: " + e); }
                    }
                });
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.bluetooth.vc.VolumeControlNativeInterface", loader),
                "onConnectionStateChanged", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        log("Native VCP state=" + p.args[0]);
                    }
                });
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.BondStateMachine", loader,
                "removeBond", BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (family(p.args[0])) log("Family native unpair requested; transition=" + p.args[1] + "; origin=" + bluetoothOrigin());
                    }
                });
            log("v0.19 selected LE activation guard, native VCP connection and policy origin observers installed");
        } catch (Throwable e) { log("Selected LE/volume hooks unavailable: " + e); }
    }
    private static boolean hasUuid(ParcelUuid[] ids, String expected) {
        if (ids != null) for (ParcelUuid id : ids) if (id != null && expected.equals(id.toString())) return true;
        return false;
    }
    private static boolean targetBytes(Object data) {
        if (!(data instanceof byte[])) return false;
        byte[] b = (byte[])data;
        String address = TARGET;
        if (address == null || b.length != 6) return false;
        String[] parts = address.split(":");
        for (int i = 0; i < 6; i++) if ((b[i] & 255) != Integer.parseInt(parts[i], 16)) return false;
        return true;
    }
    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p) {
        final ClassLoader loader = p.classLoader;
        if (!"com.android.bluetooth".equals(p.packageName)) return;
        try {
            final Class<?> adapter = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterService", p.classLoader);
            adapter.getDeclaredMethod("setLeAudioStatus", BluetoothDevice.class, int.class);
            final Class<?> hs = XposedHelpers.findClass("com.android.bluetooth.hfp.HeadsetService", p.classLoader);
            XposedHelpers.findAndHookMethod("com.android.bluetooth.hfp.HeadsetStateMachine$Connected", p.classLoader, "enter", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final BluetoothDevice d = (BluetoothDevice)XposedHelpers.getObjectField(owner(param.thisObject), "mDevice");
                        wakeupForLe(p.classLoader, d);
                    } catch (Throwable e) { log("Connection hook error: " + e); }
                }
            });
            log("v0.19 LE-advertising connection hook installed in " + p.processName);
        } catch (Throwable e) { log("Incompatible Bluetooth implementation; hook unavailable: " + e); }
        installRecoveryAndPeerHooks(p.classLoader);
        installPeerCleanupGuard(p.classLoader);
        installSelectedLeAndVolumeHooks(p.classLoader);
        installExplicitChoiceHooks(p.classLoader);
        try {
            Class<?> csip = XposedHelpers.findClass("com.android.bluetooth.csip.CsipSetCoordinatorNativeInterface", p.classLoader);
            XposedHelpers.findAndHookMethod(csip, "connect", BluetoothDevice.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (family(call.args[0])) log("Family native CSIP connect result=" + call.getResult());
                }
            });
            XposedHelpers.findAndHookMethod(csip, "onConnectionStateChanged", byte[].class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (targetBytes(call.args[0])) log("Target native CSIP connection event state=" + call.args[1]);
                }
            });
            XposedHelpers.findAndHookMethod(csip, "onDeviceAvailable", byte[].class, int.class, int.class, int.class,
                long.class, long.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if (targetBytes(call.args[0])) log("Target real CSIP group discovered: group=" + call.args[1]
                            + ", size=" + call.args[2] + ", rank=" + call.args[3]);
                    }
                });
            log("v0.19 native CSIP observers installed");
        } catch (Throwable e) { log("Native CSIP observers unavailable: " + e); }
        try {
            Class<?> nativeLe = XposedHelpers.findClass("com.android.bluetooth.le_audio.LeAudioNativeInterface", p.classLoader);
            XposedHelpers.findAndHookMethod(nativeLe, "connectLeAudio", BluetoothDevice.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (family(call.args[0])) log("Family native connectLeAudio result=" + call.getResult());
                }
            });
            XposedHelpers.findAndHookMethod(nativeLe, "onConnectionStateChanged", int.class, byte[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (targetBytes(call.args[1])) log("Target native LE connection event state=" + call.args[0]);
                }
            });
            log("v0.19 native LE connection observers installed");
        } catch (Throwable e) { log("Native LE observers unavailable: " + e); }
        try {
            XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.PhonePolicy", p.classLoader,
                "processInitProfilePriorities", BluetoothDevice.class, ParcelUuid[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (paused) return;
                            Object d = p.args[0];
                            boolean ours = target(d);
                            if (!ours && d != null) {
                                try { ours = target(XposedHelpers.callMethod(d, "findBrDevice")); } catch (Throwable ignored) {}
                            }
                            if (!ours) return;
                            ParcelUuid[] uuids = (ParcelUuid[])p.args[1];
                            log("Target discovered UUIDs: " + java.util.Arrays.toString(uuids));
                            if (uuids == null) return;
                            for (ParcelUuid uuid : uuids) {
                                if (uuid != null && "0000184e-0000-1000-8000-00805f9b34fb".equals(uuid.toString())) {
                                    boolean old = XposedHelpers.getBooleanField(p.thisObject, "mLeAudioEnabledByDefault");
                                    p.setObjectExtra("oldDefault", Boolean.valueOf(old));
                                    XposedHelpers.setBooleanField(p.thisObject, "mLeAudioEnabledByDefault", true);
                                    log("Real LE Audio UUID found; enable default policy for target during initialization");
                                    break;
                                }
                            }
                        } catch (Throwable e) { log("Policy hook error: " + e); }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object old = p.getObjectExtra("oldDefault");
                        if (old != null) try { XposedHelpers.setBooleanField(p.thisObject, "mLeAudioEnabledByDefault", (Boolean)old); }
                        catch (Throwable e) { log("Policy restore error: " + e); }
                        if (target(p.args[0]) && restoreSelectedMainPolicy(loader, "profile discovery after native re-pair")) {
                            allowWakeupHfp(loader, base());
                            wakeupForLe(loader, base());
                        }
                    }
                });
        } catch (Throwable e) { log("Policy hook unavailable: " + e); }
    }
}
