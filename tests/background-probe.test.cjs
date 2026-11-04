const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const root=path.resolve(__dirname,'..'),config=JSON.parse(fs.readFileSync(path.join(root,'assets/ubo-package.json'))),script=fs.readFileSync(path.join(root,'assets/ubo-background-probe.js'),'utf8');
async function run(change=()=>{}){
 const timers=[];const core={readyToFilter:true,isReadyPromise:Promise.resolve(),selectedFilterLists:['easylist'],supportStats:{allReadyAfter:'2000 ms'}};
 const api={runtime:{id:config.id,getManifest:()=>({version:config.version}),getBackgroundPage:cb=>cb(bg)},extension:{inIncognitoContext:false},webRequest:{onBeforeRequest:{hasListeners:()=>true}}};
 const bg={chrome:api,µBlock:core,document:{readyState:'complete'},__internetToolsEngine:{getNetworkFilterCount:()=>176058,openDashboard:async()=>({requested:true})}};
 const view={chrome:api,location:{protocol:'chrome-extension:',hostname:config.id},document:{readyState:'interactive'},setTimeout(fn){const t={fn};timers.push(t);return t},clearTimeout(t){t.cancelled=true}};
 change(view,bg,api);const pending=vm.runInNewContext(script,view);
 for(let n=0;n<10;n++){for(let i=0;i<60;i++)await Promise.resolve();for(const t of timers)if(!t.cancelled&&!t.fired){t.fired=true;t.fn();}}
 return {result:await pending,view};
}
(async()=>{
 let count=0;let r=await run();assert.equal(r.result.state,'runtime_ready');assert.equal(r.result.networkFilterCount,176058);assert.equal(r.result.requestBlockingVerified,false);assert.ok(r.view.__internetToolsRuntime);count++;
 r=await run((v,b)=>delete b.µBlock);assert.equal(r.result.state,'background_core_missing');count++;
 r=await run((v,b,a)=>a.runtime.id='another-extension');assert.equal(r.result.state,'unexpected_extension_build');count++;
 r=await run((v,b,a)=>a.extension.inIncognitoContext=true);assert.equal(r.result.state,'private_background_skipped');assert.notEqual(r.result.dashboardAvailable,true);count++;
 r=await run((v,b)=>b.__internetToolsEngine.getNetworkFilterCount=()=>0);assert.equal(r.result.state,'runtime_incomplete');count++;
 r=await run((v,b)=>b.µBlock.readyToFilter=false);assert.equal(r.result.state,'runtime_incomplete');assert.equal(r.result.dashboardAvailable,true);count++;
 r=await run((v,b)=>b.µBlock.isReadyPromise=new Promise(()=>{}));assert.equal(r.result.state,'runtime_incomplete');assert.equal(r.result.initializationComplete,false);count++;
 r=await run((v,b,a)=>a.webRequest.onBeforeRequest.hasListeners=()=>false);assert.equal(r.result.state,'runtime_incomplete');count++;
 r=await run((v,b,a)=>a.runtime.getBackgroundPage=()=>{});assert.equal(r.result.state,'background_core_missing');count++;
 r=await run(v=>delete v.chrome);assert.equal(r.result.state,'background_bindings_unavailable');count++;
 r=await run(v=>{v.location={protocol:'about:',hostname:''};});assert.equal(r.result.state,'runtime_ready');count++;
 r=await run((v,b,a)=>{a.runtime.getBackgroundPage=()=>Promise.resolve(b);v.location={protocol:'about:',hostname:''};});assert.equal(r.result.state,'runtime_ready');count++;
 r=await run((v,b,a)=>{delete a.runtime.id;v.location={protocol:'about:',hostname:''};});assert.equal(r.result.state,'unexpected_background_target');count++;
 r=await run((v,b,a)=>{v.µBlock=b.µBlock;v.__internetToolsEngine=b.__internetToolsEngine;a.runtime.getBackgroundPage=()=>{throw Error('must not request another background');};});assert.equal(r.result.state,'runtime_ready');assert.equal(r.result.backgroundAccess,'direct');count++;
 r=await run((v,b,a)=>{v.location.pathname='/background.html';v.µBlock=b.µBlock;v.__internetToolsEngine=b.__internetToolsEngine;a.runtime.getBackgroundPage=()=>{throw Error('must not request another background');};});assert.equal(r.result.state,'runtime_ready');assert.equal(r.result.backgroundAccess,'direct');count++;
 console.log('PASS: '+count+' background tests, including real getBackgroundPage context distinct from inspector document');
})().catch(e=>{console.error(e);process.exitCode=1});
