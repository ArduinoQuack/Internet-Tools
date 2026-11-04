// Executes actual shipped old/new background bundles. Native API abort is simulated.
const fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),assert=require('assert/strict');
const root=path.resolve(__dirname,'..'),baselineApk=path.resolve(process.argv[2]),candidate=path.resolve(process.argv[3]||path.join(root,'validation/extension'));
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'it-chromesetting-regression-'));
cp.execFileSync('python3',['-c',`import zipfile,io,sys
with zipfile.ZipFile(sys.argv[1]) as apk:
 with zipfile.ZipFile(io.BytesIO(apk.read('assets/ublock-origin.crx'))) as crx:crx.extractall(sys.argv[2])`,baselineApk,tmp]);
const run=(dir,extra={})=>cp.spawnSync(process.execPath,['--experimental-vm-modules',path.join(__dirname,'reference/full-startup.cjs'),dir],{cwd:root,env:{...process.env,TEST_CHROMESETTING_ABORT:'1',REFERENCE_DEPS:path.join(root,'compat/node_modules'),...extra},encoding:'utf8',timeout:30000,maxBuffer:1024*1024});
const before=run(tmp);assert.equal(before.status,86,before.stdout+before.stderr);assert.ok(before.stderr.includes('types.ChromeSetting.set'));
console.log('PASS: actual 0.8.0 startup reaches the fatal ChromeSetting.set path observed on the phone');
const after=run(candidate,{TEST_VERIFY_UNSUPPORTED_SETTINGS:'1',TEST_FORBID_PRIVACY_READ:'1'});assert.equal(after.status,0,after.stdout+after.stderr);assert.ok(after.stdout.includes('zero native ChromeSetting calls'));assert.ok(after.stdout.includes('original traffic.js listener cancels'));
fs.writeFileSync(path.join(root,'validation/chrome-setting-regression.json'),JSON.stringify({baselineManifest:JSON.parse(fs.readFileSync(path.join(tmp,'manifest.json'))).version,candidateManifest:JSON.parse(fs.readFileSync(path.join(candidate,'manifest.json'))).version,baselineExit:before.status,fixedExit:after.status,nativeAbortSimulated:true,samsungVerified:false,baselineOutput:before.stderr,fixedOutput:after.stdout},null,2));
console.log(after.stdout);
console.log('PASS: fixed full engine initializes, filters, restores test rules and survives settings changes without touching ChromeSetting; native termination remains a simulated boundary');
