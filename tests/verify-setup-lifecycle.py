#!/usr/bin/env python3
"""Run the real setup script with fake Android tools; verify logger lifetime."""
from pathlib import Path
import os, subprocess, tempfile, time, signal, sys
root = Path(__file__).resolve().parents[1]
script = root / 'scripts/enco-lc3-control.sh'
assert script.read_bytes() == (root/'assets/enco-lc3-control.sh').read_bytes(), 'APK script differs from CLI script'
fake = '''#!/usr/bin/env python3
import os, sys, time, signal, subprocess
from pathlib import Path
name=Path(sys.argv[0]).name
work=Path(os.environ['ENCO_TEST_WORK'])
def event(s):
 with (work/'events').open('a') as f: f.write(s+'\\n')
if name=='id': print('0')
elif name=='timeout':
 args=sys.argv[1:]
 while args and args[0].startswith('-'):
  args=args[2:]
 args=args[1:]
 if '90' in sys.argv and os.environ['ENCO_TEST_MODE']=='signal':
  child=subprocess.Popen(args)
  for i in range(200):
   if (work/'logger.pid').exists(): break
   time.sleep(0.05)
  time.sleep(0.15);child.send_signal(signal.SIGTERM);sys.exit(child.wait())
 os.execvp(args[0],args)
elif name=='sleep': time.sleep(0.04)
elif name=='pidof': pass
elif name=='cmd': event('radio '+sys.argv[-1])
elif name=='dumpsys': print('versionCode=17 minSdk=27')
elif name=='mktemp':
 import tempfile
 fd,p=tempfile.mkstemp(dir=work);os.close(fd);print(p)
elif name=='logcat':
 (work/'logger.pid').write_text(str(os.getpid()));event('log started')
 while True:
  print('live test log',flush=True);time.sleep(0.03)
elif name=='am':
 action=sys.argv[sys.argv.index('-a')+1].split('.')[-1];event(action)
 mode=os.environ['ENCO_TEST_MODE']
 if action=='CONFIGURE_TARGET' and mode=='failure': print('Broadcast completed: result=-1, data="Multiple paired Enco X3 main devices"')
 elif action=='CHECK_CONTROLS' and mode in ('pending','signal'): print('Broadcast completed: result=0')
 else: print('Broadcast completed: result=1')
else: raise Exception(name)
'''
with tempfile.TemporaryDirectory(prefix='enco-setup-check-') as directory:
 base=Path(directory);bindir=base/'bin';bindir.mkdir()
 for name in ('id','timeout','sleep','pidof','cmd','dumpsys','mktemp','logcat','am'):
  p=bindir/name;p.write_text(fake);p.chmod(0o700)
 modes=sys.argv[1:] or ('success','failure','pending','signal')
 for mode in modes:
  work=base/mode;work.mkdir()
  env=dict(os.environ,PATH=str(bindir)+os.pathsep+os.environ['PATH'],ENCO_TEST_WORK=str(work),ENCO_TEST_MODE=mode)
  result=subprocess.run(['sh',str(script),'setup'],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=40)
  out=result.stdout
  assert 'live test log' in out,(mode,out)
  events=(work/'events').read_text().splitlines()
  if mode != 'signal': assert events.index('log started')<events.index('radio disable'),events
  if mode=='success': assert result.returncode==0 and '完成：双耳' in out,(mode,out)
  elif mode=='failure': assert result.returncode==3 and 'NEEDS_ADDRESS' in out and 'START_LE' not in events,(mode,out)
  elif mode=='pending': assert result.returncode==3 and events.count('CHECK_CONTROLS')==15 and '完成：双耳' not in out,(mode,out)
  else: assert result.returncode==130 and '流程被停止' in out,(mode,out)
  pid=int((work/'logger.pid').read_text())
  try: os.kill(pid,0)
  except ProcessLookupError: pass
  else:
   os.kill(pid,signal.SIGKILL)
   raise AssertionError(f'{mode}: logger survived completion')
  assert sorted(p.name for p in work.iterdir())==['events','logger.pid'],'command temporary file leaked'
 print('PASS: embedded/CLI parity; live capture and logger/temp cleanup:', ', '.join(modes))
