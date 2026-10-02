#!/usr/bin/env python3
"""Exercise the production private log store, including full exports and concurrent snapshots."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[1]
java=r'''
package local.enco.lc3;
import java.io.*;
public class LogStoreCheck {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args) throws Exception {
  File f=new File(args[0],"latest.txt");LogStore store=new LogStore(f);
  check(store.snapshot().length==0,"missing log did not yield empty snapshot");
  store.reset();StringBuilder full=new StringBuilder();
  for(int i=0;i<12000;i++)full.append("完整日志第").append(i).append("行\n");
  String all=full.toString();store.append(all);
  byte[] before=store.snapshot();check(new String(before,"UTF-8").equals(all),"full export clipped to screen limit");
  store.append("后续日志\n");check(new String(before,"UTF-8").equals(all),"export snapshot changed after capture");
  check(new String(new LogStore(f).snapshot(),"UTF-8").equals(all+"后续日志\n"),"reopen lost previous operation");
  store.reset();check(store.snapshot().length==0,"new operation did not clear old private log");
  Thread one=new Thread(()->{try{for(int i=0;i<100;i++)store.append("A行\n");}catch(Exception e){throw new RuntimeException(e);}});
  Thread two=new Thread(()->{try{for(int i=0;i<100;i++)store.append("B行\n");}catch(Exception e){throw new RuntimeException(e);}});
  one.start();two.start();while(one.isAlive()||two.isAlive()){
   String snap=new String(store.snapshot(),"UTF-8");check(snap.isEmpty()||snap.endsWith("\n"),"snapshot tore a log append");
  }
  one.join();two.join();String result=new String(store.snapshot(),"UTF-8");
  check(result.split("\n").length==200,"concurrent appends lost records");
  System.out.println("PASS: production full UTF-8 log, immutable snapshot, reopen, reset and concurrent append/export");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='enco-log-store-') as d:
 p=Path(d)/'LogStoreCheck.java';p.write_text(java)
 subprocess.run(['javac','-d',d,str(root/'src/local/enco/lc3/LogStore.java'),str(p)],check=True)
 subprocess.run(['java','-cp',d,'local.enco.lc3.LogStoreCheck',d],check=True)
