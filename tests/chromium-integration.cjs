// Desktop Chromium integration in a disposable profile. Does not emulate Samsung.
const fs=require('fs'),path=require('path'),http=require('http'),os=require('os'),assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE||'playwright');
(async()=>{
 const ext=path.resolve(process.argv[2]||'validation/extension');const config=JSON.parse(fs.readFileSync('assets/ubo-package.json'));
 const binary=process.env.TEST_CHROMIUM||'/tmp/internet-tools-chromium130/chrome-linux64/chrome';
 const nonce='a'+require('crypto').randomBytes(12).toString('hex');const hits=[];let context,server;
 try{
  server=http.createServer((req,res)=>{
   hits.push(req.url);res.setHeader('cache-control','no-store');
   if(req.url.endsWith('/control.js')){res.setHeader('content-type','application/javascript');res.end('window.controlLoaded=true;');return;}
   if(req.url.endsWith('/blocked.js')){res.setHeader('content-type','application/javascript');res.end('window.blockedLoaded=true;');return;}
   if(req.url==='/'+nonce+'/'){
    res.setHeader('content-type','text/html');res.end('<!doctype html><meta charset="utf-8"><title>Internet Tools test</title><div class="it'+nonce+'">Cosmetic test</div><script src="control.js"></script><script src="blocked.js"></script>');return;
   }
   res.statusCode=404;res.end();
  });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+server.address().port;
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'internet-tools-chrome130-profile-'));
  context=await chromium.launchPersistentContext(profile,{executablePath:binary,headless:true,args:['--no-sandbox','--disable-gpu','--disable-background-networking','--disable-component-update','--disable-features=ExtensionManifestV2Disabled,ExtensionManifestV2Unsupported,ExtensionsManifestV3Only','--disable-extensions-except='+ext,'--load-extension='+ext],timeout:30000});
  let bg=context.backgroundPages().find(p=>p.url().includes(config.id));if(!bg)bg=await context.waitForEvent('backgroundpage',{timeout:20000});
  assert.ok(bg.url().startsWith('chrome-extension://'+config.id+'/'));
  const errors=[];bg.on('pageerror',e=>errors.push(e.name+':'+e.message));
  await bg.waitForFunction(()=>globalThis.µBlock?.readyToFilter===true,null,{timeout:30000});
  const probe=await bg.evaluate(fs.readFileSync('assets/ubo-background-probe.js','utf8'));assert.equal(probe.state,'runtime_ready');assert.ok(probe.networkFilterCount>10000);
  const before=await bg.evaluate(async()=>(await µBlock.loadUserFilters()).content);
  await bg.evaluate(async({origin,nonce})=>__internetToolsEngine.beginTest(origin,nonce),{origin,nonce});
  let test=context.pages().find(p=>p.url()===origin+'/'+nonce+'/');if(!test)test=await context.waitForEvent('page',{timeout:10000});
  await test.waitForLoadState('load');await test.waitForTimeout(700);
  const observed=await test.evaluate(nonce=>({controlExecuted:window.controlLoaded===true,blockedExecuted:window.blockedLoaded===true,cosmeticHidden:getComputedStyle(document.querySelector('.it'+nonce)).display==='none',scriptletApplied:window['it'+nonce]===true}),nonce);
  observed.controlReached=hits.includes('/'+nonce+'/control.js');observed.blockedReached=hits.includes('/'+nonce+'/blocked.js');
  const cleanup=await bg.evaluate(async nonce=>__internetToolsEngine.finishTest(nonce),nonce);
  assert.equal(observed.controlExecuted,true);assert.equal(observed.controlReached,true);assert.equal(observed.blockedExecuted,false);assert.equal(observed.blockedReached,false);assert.equal(observed.cosmeticHidden,true);assert.equal(observed.scriptletApplied,true);assert.equal(cleanup.blockedByUbo,true);assert.equal(cleanup.cleanupVerified,true);assert.equal(cleanup.testTabClosed,true);
  assert.equal((await bg.evaluate(async()=>(await µBlock.loadUserFilters()).content)).trim(),before.trim());
  console.log('PASS: actual Chromium native cancellation, allowed control, DOM cosmetics, MAIN-world scriptlet and exact rule cleanup',JSON.stringify({observed,cleanup}));
  const dashboard=await context.newPage();dashboard.on('pageerror',e=>errors.push(e.name+':'+e.message));
  await dashboard.goto('chrome-extension://'+config.id+'/dashboard.html');await dashboard.waitForFunction(()=>globalThis.__internetToolsDashboardReady===true,null,{timeout:20000});
  await dashboard.frameLocator('#iframe').locator('input[data-setting-name="advancedUserEnabled"]').waitFor({timeout:10000});
  await dashboard.locator('[data-pane="3p-filters.html"]').click();await dashboard.frameLocator('#iframe').locator('#lists').waitFor({timeout:10000});
  const listCount=await dashboard.frameLocator('#iframe').locator('.listEntry').count();assert.ok(listCount>5);
  await dashboard.locator('a[data-internet-tools-local]').click();await dashboard.waitForURL('**/popup-fenix.html',{timeout:10000});
  await dashboard.locator('a[href="dashboard.html"]').click();await dashboard.waitForURL('**/dashboard.html*',{timeout:10000});await dashboard.waitForFunction(()=>globalThis.__internetToolsDashboardReady===true,null,{timeout:10000});
  assert.deepEqual(errors,[]);
  const result={desktopChromium:await context.browser().version(),engineFilters:probe.networkFilterCount,observed,cleanup,dashboardTabs:true,siteControlsReturn:true,errors,samsungVerified:false};
  fs.writeFileSync('validation/chromium-integration.json',JSON.stringify(result,null,2));
  console.log('PASS: real extension dashboard/settings/filter-list UI, native messaging, site controls navigation and no JS errors',JSON.stringify(result));
 }finally{if(context)await context.close();if(server)await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1});
