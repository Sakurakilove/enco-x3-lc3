#!/usr/bin/env python3
"""Exercise production control recovery with fake native profile services."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'src/local/enco/lc3/Entry.java').read_text()
def take(a,b): return s[s.index(a):s.index(b,s.index(a))]
body=take('    private static boolean selectedRealLe(', '    private static final Set<String> controlRecoveries')
body+=take('    private static boolean repairMemberControls(', '    private static void scheduleGroupControlRecovery(')
body+=take('    private static boolean restoreNativeGroupPeer(', '    private static void processKnownCandidates(')
body+=take('    private static boolean realTargetGroup(', '    private static void enableKnownGroupIfSelected(')
body+=take('    private static String connectionLabel(', '    private static void installSelectedLeAndVolumeHooks(')
body+=take('    private static boolean hasUuid(', '    private static boolean targetBytes(')
harness=r'''
import java.util.*;
public class ControlRecoveryCheck {
 static String TARGET="main";
 static boolean paused,manualDisconnect;
 static BluetoothDevice main,verifiedPeer;
 static Service le,csip,vc,battery,mcp,tbs;
 static Object adapter=new Object();
 static boolean persist=true;
 static Map<String,String> saved=new HashMap<>();
 static class Prefs {
  Prefs edit(){return this;} Prefs putString(String k,String v){if(persist)saved.put(k,v);return this;}
  boolean commit(){return persist;}
 }
 static Prefs prefs(Object a){return new Prefs();}
 static boolean target(Object d){return d instanceof BluetoothDevice&&TARGET!=null&&TARGET.equals(((BluetoothDevice)d).address);}
 static class ParcelUuid { String value; ParcelUuid(String s){value=s;} public String toString(){return value;} }
 static class BluetoothDevice {
  static final int BOND_BONDED=12;
  String address; int bond=12,group=1; ParcelUuid[] uuids;
  BluetoothDevice(String s){address=s;} int getBondState(){return bond;}
  String getAddress(){return address;} ParcelUuid[] getUuids(){return uuids;}
 }
 static class Service {
  String name; boolean started=true,admission=true,validGroup=true;
  List<BluetoothDevice> members=new ArrayList<>();
  Set<BluetoothDevice> excludedFromCap=new HashSet<>();
  Map<BluetoothDevice,Integer> states=new HashMap<>(),policies=new HashMap<>(),auth=new HashMap<>(),queues=new HashMap<>();
  Map<BluetoothDevice,Machine> machines=new HashMap<>();
  Service(String s){name=s;}
  int state(BluetoothDevice d){return states.getOrDefault(d,0);}
  int policy(BluetoothDevice d){return policies.getOrDefault(d,name.equals("le")?100:-1);}
  void queue(BluetoothDevice d){queues.put(d,queues.getOrDefault(d,0)+1);states.put(d,1);}
 }
 static class Machine {
  Service s; BluetoothDevice d; Machine(Service s,BluetoothDevice d){this.s=s;this.d=d;}
 }
 static BluetoothDevice base(){return main;}
 static boolean family(Object d){return d==main||d==verifiedPeer;}
 static void log(String s){}
 static Object getService(ClassLoader l,String cls,String getter){
  if(cls.contains("btservice"))return adapter;
  if(cls.contains("le_audio"))return le;
  if(cls.contains("csip"))return csip;
  if(cls.contains(".vc."))return vc;
  if(cls.contains(".bas."))return battery;
  throw new AssertionError(cls);
 }
 static void requestTargetCsipConnection(ClassLoader l,BluetoothDevice d){csip.queue(d);}
 static class XposedHelpers {
  static boolean getBooleanField(Object o,String f){return ((Service)o).started;}
  static Object getObjectField(Object o,String f){
   if(f.equals("mStateMachines"))return ((Service)o).machines;
   if(f.equals("mVolumeControlNativeInterface"))return o;
   throw new AssertionError(f);
  }
  static Object callMethod(Object o,String m,Object... a){
   if(o instanceof Machine){if(!m.equals("sendMessage")||!a[0].equals(1))throw new AssertionError(m);Machine sm=(Machine)o;sm.s.queue(sm.d);return null;}
   Service s=(Service)o;
   if(m.equals("getMcpService"))return mcp;
   if(m.equals("getTbsService"))return tbs;
   if(m.equals("getDesiredGroupSize"))return 2;
   if(m.equals("getGroupDevicesOrdered"))return new ArrayList<>(s.members);
   if(m.equals("getGroupUuidMapByDevice")){Map<Integer,ParcelUuid> map=new HashMap<>();if(s.validGroup&&!s.excludedFromCap.contains(a[0]))map.put(1,new ParcelUuid("00001853-0000-1000-8000-00805f9b34fb"));return map;}
   BluetoothDevice d=(BluetoothDevice)a[0];
   switch(m){
    case "getConnectionState":return s.state(d);
    case "getConnectionPolicy":return s.policy(d);
    case "getGroupId":return d.group;
    case "okToConnect":return s.admission&&d.bond==12&&s.policy(d)!=0;
    case "getOrCreateStateMachine":return s.machines.computeIfAbsent(d,k->new Machine(s,k));
    case "connectIfPossible":if(s.policy(d)==0)return false;s.queue(d);return true;
    case "getDeviceAuthorization":int auth=s.auth.getOrDefault(d,0);if(auth==0&&(csip.policy(d)>0||le.policy(d)>0)){auth=1;s.auth.put(d,1);}return auth;
    default:throw new AssertionError(m);
   }
  }
 }
__PRODUCTION__
 static void reset(){
  TARGET="main";paused=manualDisconnect=false;persist=true;saved.clear();main=new BluetoothDevice("main");verifiedPeer=new BluetoothDevice("peer");
  le=new Service("le");csip=new Service("csip");vc=new Service("vc");battery=new Service("battery");mcp=new Service("mcp");tbs=new Service("tbs");
  for(BluetoothDevice d:new BluetoothDevice[]{main,verifiedPeer}){le.states.put(d,2);csip.states.put(d,2);vc.states.put(d,2);}
 }
 static int queued(Service s,BluetoothDevice d){return s.queues.getOrDefault(d,0);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] a){
  reset();vc.states.put(main,0);check(!repairConnectedGroupControls(null)&&queued(vc,main)==1,"missing main VCP not repaired when peer connected");
  check(!repairConnectedGroupControls(null)&&queued(vc,main)==1,"connecting VCP queued twice");
  vc.states.put(main,2);check(repairConnectedGroupControls(null),"completed two-ear VCP not recognized");
  reset();vc.states.put(main,0);le.policies.put(main,-1);check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"UNKNOWN LE policy admitted early");
  le.policies.put(main,100);check(!repairConnectedGroupControls(null)&&queued(vc,main)==1,"late ALLOWED did not repair earlier main");
  reset();vc.states.put(main,0);paused=true;check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"paused recovery");
  paused=false;manualDisconnect=true;check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"manual disconnect recovery");
  manualDisconnect=false;TARGET=null;check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"unconfigured recovery");
  reset();vc.states.put(main,0);le.policies.put(main,0);check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"explicit LE off ignored");
  reset();vc.states.put(main,0);main.bond=10;check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"unbonded main admitted");
  reset();vc.states.put(verifiedPeer,0);verifiedPeer.group=2;check(!repairConnectedGroupControls(null)&&queued(vc,verifiedPeer)==0,"foreign stored peer repaired");
  reset();vc.states.put(verifiedPeer,0);verifiedPeer.bond=10;check(repairConnectedGroupControls(null)&&queued(vc,verifiedPeer)==0,"unbonded peer repaired");
  reset();csip.validGroup=false;vc.states.put(main,0);check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"unverified native CAP group admitted");
  reset();vc.states.put(main,0);vc.policies.put(main,0);check(repairConnectedGroupControls(null)&&queued(vc,main)==0,"explicit VCP off ignored");
  reset();vc.states.put(main,0);vc.admission=false;check(!repairConnectedGroupControls(null)&&queued(vc,main)==0,"native admission bypassed");
  reset();csip.states.put(main,0);check(!repairConnectedGroupControls(null)&&queued(csip,main)==1,"CSIP recovery missing");
  reset();csip.states.put(main,0);csip.policies.put(main,0);check(repairConnectedGroupControls(null)&&queued(csip,main)==0,"explicit CSIP off ignored");
  reset();main.uuids=new ParcelUuid[]{new ParcelUuid("0000180f-0000-1000-8000-00805f9b34fb")};
  check(!repairConnectedGroupControls(null)&&queued(battery,main)==1,"real BAS not recovered");
  check(!repairConnectedGroupControls(null)&&queued(battery,main)==1,"connecting BAS queued twice");
  battery.states.put(main,2);check(repairConnectedGroupControls(null),"BAS readiness not recognized");
  reset();main.uuids=new ParcelUuid[]{new ParcelUuid("0000180f-0000-1000-8000-00805f9b34fb")};battery.policies.put(main,0);
  check(repairConnectedGroupControls(null)&&queued(battery,main)==0,"disabled BAS reconnected");
  reset();check(repairConnectedGroupControls(null)&&queued(battery,main)==0,"absent BAS fabricated");
  check(mcp.auth.get(main)==1&&tbs.auth.get(verifiedPeer)==1,"normal media/call UNKNOWN resolution missing");
  reset();mcp.auth.put(main,2);tbs.auth.put(verifiedPeer,2);check(repairConnectedGroupControls(null)&&mcp.auth.get(main)==2&&tbs.auth.get(verifiedPeer)==2,"explicit authorization denial overwritten");
  reset();vc=null;check(!repairConnectedGroupControls(null)&&mcp.auth.get(main)==1&&tbs.auth.get(verifiedPeer)==1,"missing VCP prevented other controls");
  reset();mcp=tbs=null;check(repairConnectedGroupControls(null),"optional absent server failed recovery");
  reset();check(bothGroupControlsReady(null),"complete stereo group rejected");
  reset();verifiedPeer=null;check(!bothGroupControlsReady(null),"missing second member reported complete");
  reset();le.states.put(verifiedPeer,0);check(!bothGroupControlsReady(null),"one-ear LE reported stereo complete");
  reset();vc.states.put(main,0);check(!bothGroupControlsReady(null)&&queued(vc,main)==1,"stereo readiness did not repair missing main VCP");
  reset();csip.states.put(verifiedPeer,1);check(!bothGroupControlsReady(null),"connecting CSIP reported ready");
  reset();vc.states.put(verifiedPeer,0);vc.policies.put(verifiedPeer,0);check(!bothGroupControlsReady(null)&&queued(vc,verifiedPeer)==0,"disabled VCP reported full readiness");
  reset();verifiedPeer.group=2;check(!bothGroupControlsReady(null),"foreign peer reported stereo complete");
  reset();verifiedPeer.bond=10;check(!bothGroupControlsReady(null),"unbonded second member reported ready");
  reset();BluetoothDevice realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;vc.states.put(realPeer,0);
  check(!bothGroupControlsReady(null)&&verifiedPeer==realPeer&&queued(vc,realPeer)==1&&"peer".equals(saved.get("verified_peer")),"fresh public config missed native cached peer VCP");
  vc.states.put(realPeer,2);check(bothGroupControlsReady(null),"recovered native cached stereo not ready");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;persist=false;
  check(!restoreNativeGroupPeer(null)&&verifiedPeer==null,"failed persistence trusted cached peer");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;csip.excludedFromCap.add(realPeer);
  check(!restoreNativeGroupPeer(null)&&verifiedPeer==null,"peer without native CAP membership accepted");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;realPeer.group=2;
  check(!restoreNativeGroupPeer(null),"foreign LE group cached peer accepted");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;realPeer.bond=10;
  check(!restoreNativeGroupPeer(null),"unpaired cache member trusted");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer,new BluetoothDevice("other")));verifiedPeer=null;
  check(!restoreNativeGroupPeer(null),"ambiguous cached members accepted");
  reset();realPeer=verifiedPeer;csip.members.addAll(Arrays.asList(main,realPeer));verifiedPeer=null;paused=true;
  check(!restoreNativeGroupPeer(null),"paused cached peer recovery");
  paused=false;manualDisconnect=true;check(!restoreNativeGroupPeer(null),"manual disconnect cached recovery");
  manualDisconnect=false;le.policies.put(main,0);check(!restoreNativeGroupPeer(null),"disabled LE cached recovery");
  reset();verifiedPeer=null;check(controlReadinessDetails(null).contains("尚未由原生 CSIS 确认"),"missing member diagnostic hidden");
  reset();vc.states.put(verifiedPeer,0);String detail=controlReadinessDetails(null);
  check(detail.contains("主耳[")&&detail.contains("另一耳[")&&detail.contains("VCP=未连"),"per-ear missing VCP detail hidden");
  reset();paused=true;check(controlReadinessDetails(null).contains("暂停"),"pause diagnostic missing");
  System.out.println("PASS: production group control recovery: delayed policy/main VCP, no duplicate connect, native group/bond/admission, disabled profiles, CSIP, BAS and media/call authorization");
 }
}
'''.replace('__PRODUCTION__',body)
with tempfile.TemporaryDirectory(prefix='enco-controls-') as d:
 p=Path(d)/'ControlRecoveryCheck.java';p.write_text(harness)
 subprocess.run(['javac',str(p)],check=True)
 subprocess.run(['java','-cp',d,'ControlRecoveryCheck'],check=True)
