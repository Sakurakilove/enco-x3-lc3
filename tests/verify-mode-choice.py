#!/usr/bin/env python3
"""Run production choice handling and the actual delayed HFP advertising worker."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'src/local/enco/lc3/Entry.java').read_text()
def take(a,b):return s[s.index(a):s.index(b,s.index(a))]
body=take('    private static boolean explicitChoiceCaller(', '    private static boolean restoreSelectedMainPolicy(')
body+=take('    private static void wakeupForLe(', '    private static String bluetoothOrigin(')
# Execute the production adapter callbacks as well as the delayed worker.
for hook, next_hook, name in (
    ('"disconnectAllEnabledProfiles"', 'XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.PhonePolicy"', 'disconnect'),
    ('"connectAllEnabledProfiles"', 'XposedHelpers.findAndHookMethod(leClass, "connect"', 'reconnect'),
):
    start=s.index(hook)
    callback=s[s.index('@Override protected void beforeHookedMethod', start):s.index(next_hook,start)]
    callback=callback[:callback.rindex('            });')]
    body+=callback.replace('@Override protected void beforeHookedMethod(MethodHookParam p)', 'private static void '+name+'(XC_MethodHook.MethodHookParam p)',1)
harness=r'''
import java.util.*;
import java.util.concurrent.atomic.*;
public class ModeChoiceCheck {
 static volatile String TARGET="main";
 static volatile boolean paused,manualDisconnect,rememberedLe=true,advertisingReady;
 static long lastWakeup;
 static AtomicInteger leChoiceGeneration=new AtomicInteger(),atCommands=new AtomicInteger(),leQueues=new AtomicInteger(),csipQueues=new AtomicInteger();
 static AtomicBoolean attempted=new AtomicBoolean(),workerBusy=new AtomicBoolean();
 static BluetoothDevice main=new BluetoothDevice("main"),peer=new BluetoothDevice("peer");
 static Object adapter=new Object(),le=new Object(),hs=new Object();
 static int policy=100,leState=0,hfpState=2;
 static BluetoothDevice verifiedPeer=peer;
 static Class<?> leClass=Object.class;
 static String[] responses={"+MTK=FFFAFB000101FF"};
 static boolean[] responseResults={true};
 static void restorePeerRecord(Object adapter){}
 static void allowWakeupHfp(ClassLoader l,BluetoothDevice d){}
 static void connectBondedPeer(ClassLoader l,BluetoothDevice d){}
 static int retries;
 static void retryExplicitLeWakeup(ClassLoader l){retries++;}
 static final ClassLoader loader=null;
 static XC_MethodHook.MethodHookParam event(BluetoothDevice d){
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{d};return p;
 }
 static Map<String,Boolean> saved=new HashMap<>(); static boolean commitAllowed=true;
 static class BluetoothDevice {static final int BOND_BONDED=12;String address;int bond=12;BluetoothDevice(String s){address=s;}int getBondState(){return bond;}}
 static class Source {String pkg;Source(String s){pkg=s;}}
 static class Prefs { Prefs edit(){return this;} Prefs putBoolean(String k,boolean v){if(commitAllowed)saved.put(k,v);return this;} boolean commit(){return commitAllowed;} }
 static Prefs prefs(Object a){return new Prefs();}
 static BluetoothDevice base(){return main;}
 static boolean family(Object d){return d==main||d==peer;}
 static boolean target(Object d){return d==main&&"main".equals(TARGET);}
 static void log(String s){}
 static boolean restoreSelectedMainPolicy(ClassLoader l,String why){return rememberedLe&&!paused&&policy==100;}
 static void requestTargetCsipConnection(ClassLoader l,BluetoothDevice d){csipQueues.incrementAndGet();}
 static boolean requestTargetLeConnection(Object le,BluetoothDevice d){leQueues.incrementAndGet();return true;}
 static Object getService(ClassLoader l,String cls,String getter){if(cls.contains("le_audio"))return le;if(cls.contains("hfp"))return hs;return adapter;}
 static class SystemClock {static long elapsedRealtime(){return 60000;}}
 static class XC_MethodHook {
  static class MethodHookParam {Object[] args;Object result;Object thisObject;Object getResult(){return result;}}
  protected void afterHookedMethod(MethodHookParam p){}
  static class Unhook {void unhook(){XposedHelpers.observer=null;}}
 }
 static class XposedHelpers {
  static XC_MethodHook observer;
  static Class<?> findClass(String s,ClassLoader l){return Object.class;}
  static XC_MethodHook.Unhook findAndHookMethod(Object... args){observer=(XC_MethodHook)args[args.length-1];return new XC_MethodHook.Unhook();}
  static Object callStaticMethod(Class<?> cls,String m){return le;}
  static Object callMethod(Object o,String m,Object... args){
   if(o instanceof Source&&m.equals("getPackageName"))return ((Source)o).pkg;
   if(m.equals("getConnectionPolicy"))return policy;
   if(m.equals("getConnectionState"))return o==hs?hfpState:leState;
   if(o==adapter&&m.equals("setLeAudioStatus")){
    atCommands.incrementAndGet();
    if(observer!=null)for(int i=0;i<responses.length;i++){XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{main,responses[i]};p.result=responseResults[i];observer.afterHookedMethod(p);}return null;
   }
   throw new IllegalArgumentException(m);
  }
 }
__PRODUCTION__
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void reset(){
  check(!workerBusy.get(),"previous worker still running");TARGET="main";paused=manualDisconnect=advertisingReady=false;rememberedLe=true;
  retries=0;responses=new String[]{"+MTK=FFFAFB000101FF"};responseResults=new boolean[]{true};lastWakeup=0;policy=100;leState=0;hfpState=2;leChoiceGeneration.set(0);atCommands.set(0);leQueues.set(0);csipQueues.set(0);attempted.set(false);saved.clear();commitAllowed=true;main.bond=12;
 }
 static void awaitWorker() throws Exception {
  long deadline=System.currentTimeMillis()+7000;
  while(workerBusy.get()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
  check(!workerBusy.get(),"worker did not retire");
 }
 public static void main(String[] args)throws Exception{
  reset();disconnect(event(main));reconnect(event(main));
  check(!manualDisconnect&&leQueues.get()==1&&csipQueues.get()==1&&retries==1,"explicit reconnect stayed blocked after manual disconnect");
  reset();disconnect(event(peer));reconnect(event(peer));check(!manualDisconnect,"peer reconnect stayed blocked");
  reset();disconnect(event(main));reconnect(event(new BluetoothDevice("foreign")));check(manualDisconnect,"foreign reconnect cleared manual stop");
  reset();disconnect(event(main));paused=true;reconnect(event(main));check(manualDisconnect&&leQueues.get()==0,"paused reconnect resumed module");
  reset();disconnect(event(main));rememberedLe=false;reconnect(event(main));check(manualDisconnect&&leQueues.get()==0,"classic reconnect selected LE");
  reset();disconnect(event(main));main.bond=10;reconnect(event(main));check(manualDisconnect&&leQueues.get()==0,"unpaired reconnect resumed module");
  reset();wakeupForLe(null,main);Thread.sleep(100);int oldDisconnectEpoch=leChoiceGeneration.get();
  disconnect(event(main));reconnect(event(main));leQueues.set(0);csipQueues.set(0);awaitWorker();
  check(atCommands.get()==0&&!leWorkCurrent("main",oldDisconnectEpoch),"disconnect/reconnect revived old advertising worker");
  reset();responses=new String[]{"+MTK=UNRELATED"};wakeupForLe(null,main);awaitWorker();
  check(!advertisingReady&&leQueues.get()==0&&csipQueues.get()==0,"unrelated MTK command confirmed advertising");
  reset();responseResults=new boolean[]{false};wakeupForLe(null,main);awaitWorker();
  check(!advertisingReady&&leQueues.get()==0,"failed advertising command confirmed readiness");
  reset();responses=new String[]{"+MTK=FFFAFB000101FF","+MTK=UNRELATED"};responseResults=new boolean[]{true,false};
  wakeupForLe(null,main);awaitWorker();check(advertisingReady&&leQueues.get()==1,"unrelated failed command erased genuine confirmation");
  reset();rememberedLe=false;policy=-1;wakeupForLe(null,main);check(!workerBusy.get()&&atCommands.get()==0,"unselected UNKNOWN classic reconnect started LE advertising");
  reset();wakeupForLe(null,main);check(workerBusy.get(),"selected wakeup did not start");Thread.sleep(100);
  recordExplicitLeChoice(null,main,false,"settings off");awaitWorker();
  check(atCommands.get()==0&&leQueues.get()==0&&csipQueues.get()==0,"manual off did not cancel pending 3-second worker");
  check(paused&&!rememberedLe&&saved.get("paused")&&!saved.get("le_selected"),"manual off not persisted");
  reset();wakeupForLe(null,main);Thread.sleep(100);int old=leChoiceGeneration.get();
  recordExplicitLeChoice(null,peer,false,"peer row off");recordExplicitLeChoice(null,main,true,"settings on");awaitWorker();
  check(atCommands.get()==0&&!paused&&rememberedLe&&!leWorkCurrent("main",old),"rapid off/on revived a stale worker");
  reset();wakeupForLe(null,main);
  long deadline=System.currentTimeMillis()+4000;while(atCommands.get()==0&&System.currentTimeMillis()<deadline)Thread.sleep(10);
  check(atCommands.get()==1,"selected worker did not send genuine advertising request");
  recordExplicitLeChoice(null,main,false,"off during 1.5-second wait");awaitWorker();
  check(leQueues.get()==0&&csipQueues.get()==0&&!advertisingReady,"manual off during advertising wait queued LE");
  reset();wakeupForLe(null,main);awaitWorker();check(atCommands.get()==1&&csipQueues.get()==1&&leQueues.get()==1,"selected normal wakeup regressed");
  reset();recordExplicitLeChoice(null,new BluetoothDevice("foreign"),false,"foreign");check(!paused&&rememberedLe&&saved.isEmpty(),"foreign device changed selection");
  check(explicitChoiceCaller(new Source("com.android.settings"))&&explicitChoiceCaller(new Source("com.heytap.headset")),"external control caller rejected");
  check(!explicitChoiceCaller(new Source("com.android.bluetooth"))&&!explicitChoiceCaller(new Source(null)),"automatic/internal update became user intent");
  reset();int epoch=leChoiceGeneration.get();TARGET="other";check(!leWorkCurrent("main",epoch),"old target work accepted");
  reset();main.bond=10;check(!leWakeupStillAllowed(null,main,"main",0),"unpaired wakeup allowed");
  reset();policy=0;check(!leWakeupStillAllowed(null,main,"main",0),"disabled policy allowed wakeup");
  reset();recordExplicitLeChoice(null,main,false,"off");paused=saved.get("paused");rememberedLe=saved.get("le_selected");leChoiceGeneration.set(0);
  check(!leWorkCurrent("main",0),"saved classic choice resumed after process restart");
  reset();commitAllowed=false;recordExplicitLeChoice(null,main,false,"off storage failure");check(paused&&!rememberedLe,"failed persistence ignored accepted off in current process");
  System.out.println("PASS: production delayed worker cancelled before AT and after advertising; rapid off/on epochs; persisted classic choice; genuine selected wakeup preserved; manual reconnect and stale disconnect epochs; exact AT confirmation");
 }
}
'''.replace('__PRODUCTION__',body)
with tempfile.TemporaryDirectory(prefix='enco-choice-') as d:
 p=Path(d)/'ModeChoiceCheck.java';p.write_text(harness)
 subprocess.run(['javac',str(p)],check=True)
 subprocess.run(['java','-cp',d,'ModeChoiceCheck'],check=True)
