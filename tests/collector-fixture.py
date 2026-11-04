from pathlib import Path
import tempfile,subprocess,os,json,time,threading,queue,sys
root=Path(__file__).resolve().parents[1];tmp=Path(tempfile.mkdtemp(prefix='it-collector-fixture-'))
# A disposable process with a browser-like argv[0], in the same PID namespace.
identity='com.sec.android.app.sbrowser:fixture-'+tmp.name
browser=subprocess.Popen(['bash','-c','exec -a '+identity+' sleep 30'])
time.sleep(0.1)
# Some managed test executors mount a host /proc across a child PID namespace.
proc_pid=next(int(p.name) for p in Path('/proc').iterdir() if p.name.isdigit() and (p/'cmdline').exists() and (p/'cmdline').read_bytes().split(b'\0')[0]==identity.encode())
logcat=tmp/'logcat';logcat.write_text('''#!/usr/bin/python3
import os,time
pid=os.environ['TEST_BROWSER_PID']
for row in [
 f'11-03 00:00:00.001  {pid}  {pid} I InternetToolsTrace: own_popup_committed',
 f'11-03 00:00:00.002  {pid}  {pid} F libc: Fatal signal 11 in browser renderer',
 '11-03 00:00:00.003  1  1 E AndroidRuntime: UNRELATED_PRIVATE_DATA',
 f'11-03 00:00:00.004  {pid}  {pid} I chromium: [INFO:CONSOLE] PAGE_PRIVATE_DATA',
 f'11-03 00:00:00.005  {pid}  {pid} E chromium: chrome-extension://odkabbfgkiaeajgdfcfhnbkmjjkolehf/js/start-compat.js:12 TypeError https://secret.test/path token=SECRET_VALUE',
 '11-03 00:00:00.006  1  1 I ActivityManager: Process com.sec.android.app.sbrowser:fixture has died',
]: print(row,flush=True)
time.sleep(30)
''');logcat.chmod(0o755)
dumpsys=tmp/'dumpsys';dumpsys.write_text('''#!/usr/bin/python3
print('ApplicationExitInfo: process=com.sec.android.app.sbrowser:fixture pid=42 reason=5 (NATIVE CRASH) status=11 timestamp=123')
print('not selected field data')
''');dumpsys.chmod(0o755)
env=os.environ.copy();env['PATH']=str(tmp)+':'+env['PATH'];env['TEST_BROWSER_PID']=str(proc_pid)
classes=Path(os.environ.get('INTERNET_TOOLS_BUILD_DIR',str(root/'build')))/'intermediates/javac/debug/compileDebugJavaWithJavac/classes'
cmd=[os.environ.get('JAVA','java'),'-cp',str(classes)+':'+os.environ['JSON_JAR'],'dev.dov.internettools.DiagnosticCollectorMain']
p=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
q=queue.Queue();threading.Thread(target=lambda:[q.put(line) for line in p.stdout],daemon=True).start()
reports=[];deadline=time.monotonic()+15
try:
 while time.monotonic()<deadline:
  line=q.get(timeout=10)
  if line.startswith('{'):
   report=json.loads(line);reports.append(report)
   if not report['complete'] and len(report['logcat'])>=3:
    p.stdin.write('x');p.stdin.flush();break
 while time.monotonic()<deadline:
  line=q.get(timeout=10)
  if line.startswith('{'):
   report=json.loads(line);reports.append(report)
   if report['complete']:break
 assert reports and reports[-1]['complete'],reports
 text=json.dumps(reports[-1]);assert 'own_popup_committed' in text;assert 'Fatal signal 11' in text
 assert 'UNRELATED_PRIVATE_DATA' not in text;assert 'PAGE_PRIVATE_DATA' not in text;assert 'secret.test' not in text;assert 'SECRET_VALUE' not in text
 assert 'NATIVE CRASH' in text;assert any(not r['complete'] for r in reports)
 p.wait(timeout=5);assert p.returncode==0,p.stderr.read()
 (root/'validation/collector-fixture.json').write_text(json.dumps({'passed':True,'snapshotCount':len(reports),'scope':'Real collector JVM and subprocess stream with simulated Android commands and one disposable browser-like process','final':reports[-1]},indent=2))
 print('PASS: collector main process streams partial snapshots, filters own process logs, redacts values, includes exit reasons and terminates on parent signal')
finally:
 p.kill() if p.poll() is None else None
 browser.terminate();browser.wait()
