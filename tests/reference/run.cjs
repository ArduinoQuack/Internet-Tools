const fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'../..'),deps=process.env.REFERENCE_DEPS||path.join(root,'compat/node_modules');
 const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'internet-tools-reference-')),baseline=path.join(tmp,'original'),candidate=path.join(tmp,'candidate'),store=path.join(tmp,'storage.json');
 const env={...process.env,REFERENCE_DEPS:deps,ESBUILD_PACKAGE:path.join(deps,'esbuild'),ACORN_PACKAGE:path.join(deps,'acorn')};
 cp.execFileSync('python3',['-c','import zipfile,sys\nwith zipfile.ZipFile(sys.argv[1]) as z:z.extractall(sys.argv[2])',path.join(root,'upstream/uBlock-1.75.0-original.crx'),baseline]);
 await require(path.join(deps,'esbuild')).build({entryPoints:[path.join(baseline,'js/start.js')],outfile:path.join(baseline,'js/start-compat.js'),bundle:true,format:'iife',target:'chrome130',external:['/js/*'],logLevel:'silent'});
 cp.execFileSync(process.execPath,[path.join(root,'compat/build.cjs'),candidate],{env,stdio:'pipe'});
 // Same final manifest/key/version as the shipped artifact; build script produces source before signing.
 const production=JSON.parse(fs.readFileSync(path.join(root,'assets/ubo-package.json')));
 const manifest=JSON.parse(fs.readFileSync(path.join(candidate,'manifest.json')));manifest.key=fs.readFileSync(path.join(root,'assets/ubo-public-key.txt'),'utf8').trim();manifest.version=production.version;manifest.version_name=production.upstreamVersion;manifest.name='uBlock Origin';fs.writeFileSync(path.join(candidate,'manifest.json'),JSON.stringify(manifest));
 const run=(dir,extra)=>cp.spawnSync(process.execPath,['--experimental-vm-modules',path.join(__dirname,'full-startup.cjs'),dir],{env:{...env,...extra},encoding:'utf8',timeout:30000,maxBuffer:1024*1024});
 const before=run(baseline,{TEST_TIMEOUT:'3500',STRING_OVERLOAD_HANG:'0',TEST_STORE:path.join(tmp,'baseline-storage.json')});
 assert.equal(before.status,1);assert.ok(before.stdout.includes('"ready": false'));assert.ok(before.stderr.includes('Full uBO did not initialize'));console.log('PASS: unmodified startup reproduces managed-storage callback hang');
 const first=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store});assert.equal(first.status,0,first.stdout+first.stderr);assert.ok(!first.stdout.includes('FIRST_INSTALL_RESTART'));console.log('PASS: cold install loads and filters in one background without a runtime reload');
 const after=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store});assert.equal(after.status,0,after.stdout+after.stderr);process.stdout.write(after.stdout);
 for(const style of ['promise','hybrid']){const result=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store,API_RETURN_STYLE:style});assert.equal(result.status,0,result.stdout+result.stderr);console.log('PASS: full engine, network decisions and cleanup with '+style+' storage completion');}
 const split=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store,SPLIT_NAMESPACE:'1'});assert.equal(split.status,0,split.stdout+split.stderr);console.log('PASS: distinct browser namespace with Promise-only storage is supported');
 const frozen=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store,FROZEN_NATIVE_DEADLINES:'1'});assert.equal(frozen.status,0,frozen.stdout+frozen.stderr);console.log('PASS: startup and real filtering do not depend on managed-response or API-deadline timers');
 const single=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:store,MULTI_KEY_READ_HANG:'1',FROZEN_NATIVE_DEADLINES:'1'});assert.equal(single.status,0,single.stdout+single.stderr);console.log('PASS: engine initializes and filters with single-key-only native storage and no native deadline timers');
 const slowStore=path.join(tmp,'slow-version-storage.json');for(let launch=0;launch<2;launch++){const slow=run(candidate,{STRING_OVERLOAD_HANG:'1',TEST_STORE:slowStore,DELAY_VERSION_COMMIT:'1'});assert.equal(slow.status,0,slow.stdout+slow.stderr);assert.ok(!slow.stdout.includes('FIRST_INSTALL_RESTART'));assert.equal(JSON.parse(fs.readFileSync(slowStore)).version,undefined);}console.log('PASS: repeated cold starts filter without reload loops when version metadata is not committed');
 const functions=cp.execFileSync(process.execPath,[path.join(__dirname,'scriptlet-equivalence.cjs'),baseline,candidate],{env,encoding:'utf8'});process.stdout.write(functions);
 console.log('PASS: complete reference suite. This does not test Samsung native request cancellation.');
})().catch(e=>{console.error(e);process.exitCode=1});
