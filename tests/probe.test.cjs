const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const script = fs.readFileSync(path.join(__dirname,'../assets/ubo-probe.js'),'utf8');
async function run(configure = () => {}, expireMs) {
    const timers = [], requests = [];
    const counts = {netFilterCount:12345,cosmeticFilterCount:345,autoUpdate:true};
    Object.defineProperty(counts,'current',{get(){throw Error('Do not inspect user list details');}});
    const respond = what => {
        requests.push(what);
        switch(what) {
            case 'getAppData': return {name:'uBlock Origin',version:'1.75.0'};
            case 'readyToFilter': return true;
            case 'getLists': return counts;
            default: throw Error('Unexpected diagnostic request '+what);
        }
    };
    let disconnected=false, messageListener, disconnectListener;
    const port = {
        onMessage:{addListener(f){messageListener=f;}},
        onDisconnect:{addListener(f){disconnectListener=f;}},
        postMessage(packet){
            assert.equal(packet.channel,'dashboard');
            const result=respond(packet.msg.what);
            queueMicrotask(()=>messageListener({msgId:packet.msgId,msg:result}));
        },
        disconnect(){disconnected=true;if(disconnectListener)disconnectListener();}
    };
    const sandbox = {
        location:{protocol:'chrome-extension:',hostname:'fkgkibajhfbepljeaefdnfnegdcjomkh'},
        // Reproduces the Samsung report: there is NO getBackgroundPage function.
        chrome:{runtime:{getManifest(){return {permissions:['webRequestBlocking']};},connect(){return port;}}},
        vAPI:{messaging:{send(channel,request){assert.equal(channel,'dashboard');return Promise.resolve(respond(request.what));}}},
        setTimeout(fn,ms){const token={fn,ms,cancelled:false};timers.push(token);return token;},
        clearTimeout(token){if(token)token.cancelled=true;}
    };
    configure(sandbox,counts,respond);
    const ret=vm.runInNewContext(script,sandbox);
    for(let i=0;i<80;i++)await Promise.resolve();
    // Advance the readiness waits, but leave query/watchdog failures under each test's control.
    for(let turn=0;turn<20;turn++) {
        const token=timers.find(t=>t.ms===750&&!t.cancelled&&!t.fired);
        if(!token)break;
        token.fired=true;token.fn();
        for(let i=0;i<80;i++)await Promise.resolve();
    }
    if(expireMs!==undefined){
        for(const token of timers)if(token.ms===expireMs&&!token.cancelled)token.fn();
        for(let i=0;i<80;i++)await Promise.resolve();
    }
    return {out:sandbox.__internetPrivateUboProbe_v1,ret,requests,disconnected};
}
(async()=>{
    let tests=0;
    const reported=await run();
    assert.equal(reported.out.state,'runtime_ready');
    assert.equal(reported.out.capabilities.getBackgroundPage,false);
    assert.equal(reported.out.requestListenerRegistered,null);
    assert.equal(reported.out.requestBlockingVerified,false);
    assert.equal(reported.out.filterAutoUpdate,true);
    assert.deepEqual(reported.requests,['getAppData','readyToFilter','getLists']);tests++;
    const port=await run(s=>{delete s.vAPI;});
    assert.equal(port.out.state,'runtime_ready');assert.equal(port.out.transport,'ubo_runtime_port');assert.equal(port.disconnected,true);tests++;
    const alternate=await run(s=>{delete s.vAPI;s.browser=s.chrome;s.chrome={};});
    assert.equal(alternate.out.state,'runtime_ready');assert.equal(alternate.out.transport,'ubo_runtime_port');tests++;
    const onlyVapi=await run(s=>{delete s.chrome;});
    assert.equal(onlyVapi.out.state,'runtime_ready');assert.equal(onlyVapi.out.blockingPermission,null);tests++;
    const wrong=await run(s=>{s.location.protocol='https:';s.location.hostname='example.com';});
    assert.equal(wrong.ret,'wrong_origin');assert.equal(wrong.out,undefined);assert.deepEqual(wrong.requests,[]);tests++;
    const missing=await run(s=>{delete s.vAPI;delete s.chrome;});
    assert.equal(missing.out.state,'unsupported_extension_messaging');tests++;
    const notReady=await run(s=>{s.vAPI.messaging.send=(channel,r)=>Promise.resolve(r.what==='getAppData'?{name:'uBlock Origin',version:'1.75.0'}:false);});
    assert.equal(notReady.out.state,'runtime_incomplete');assert.equal(notReady.out.readyToFilter,false);tests++;
    let readinessCalls=0;
    const eventuallyReady=await run((s,c,respond)=>{
        s.vAPI.messaging.send=(channel,r)=>Promise.resolve(r.what==='readyToFilter'&&++readinessCalls<3?false:respond(r.what));
    });
    assert.equal(eventuallyReady.out.state,'runtime_ready');assert.equal(eventuallyReady.out.readinessChecks,3);tests++;
    const stalledWithStartup=await run(s=>{
        const bg={µBlock:{readyToFilter:false,supportStats:{cacheBackend:'indexedDB',allReadyAfter:'?',privateData:'secret'},selectedFilterLists:['private-list'],apiErrorCount:0},vAPI:{T0:Date.now()-10000},chrome:{runtime:{},storage:{local:{get(key,cb){assert.deepEqual(Array.from(key),['version','cachedManagedStorage']);cb({version:'1.75.0'});}}}}};
        s.chrome.runtime.getBackgroundPage=cb=>cb(bg);
        s.vAPI.messaging.send=(channel,r)=>Promise.resolve(r.what==='getAppData'?{name:'uBlock Origin',version:'1.75.0'}:false);
    });
    assert.equal(stalledWithStartup.out.state,'runtime_incomplete');assert.equal(stalledWithStartup.out.readinessChecks,17);
    assert.equal(stalledWithStartup.out.startup.storedVersion,'1.75.0');
    assert.equal(stalledWithStartup.out.startup.selectedListCount,1);
    assert.equal(stalledWithStartup.out.startup.supportStats.privateData,undefined);
    assert.equal(stalledWithStartup.out.startup.selectedFilterLists,undefined);tests++;
    const startupHung=await run(s=>{
        s.chrome.runtime.getBackgroundPage=cb=>cb({chrome:{runtime:{},storage:{local:{get(){}}}}});
        s.vAPI.messaging.send=(channel,r)=>Promise.resolve(r.what==='getAppData'?{name:'uBlock Origin',version:'1.75.0'}:false);
    },1500);
    assert.equal(startupHung.out.state,'runtime_incomplete');assert.equal(startupHung.out.startup.storageRead,'timeout');tests++;
    const managedStall=await run(s=>{
        s.chrome.runtime.getBackgroundPage=cb=>cb({µBlock:{readyToFilter:false,supportStats:{}},chrome:{runtime:{},storage:{
            local:{get(keys,cb){cb({version:'1.75.0'});}},
            managed:{get(key,cb){assert.equal(key,'adminSettings');}}
        }}});
        s.vAPI.messaging.send=(channel,r)=>Promise.resolve(r.what==='getAppData'?{name:'uBlock Origin',version:'1.75.0'}:false);
    },1500);
    assert.equal(managedStall.out.startup.storageRead,'responded');
    assert.equal(managedStall.out.startup.managedStorageRead,'timeout');
    assert.equal(managedStall.out.startup.managedCachePresent,false);tests++;
    const noRules=await run((s,c)=>{c.netFilterCount=0;});
    assert.equal(noRules.out.state,'runtime_incomplete');tests++;
    const invalidRules=await run((s,c)=>{c.netFilterCount='12345';});
    assert.equal(invalidRules.out.state,'runtime_incomplete');tests++;
    const noPermission=await run(s=>{s.chrome.runtime.getManifest=()=>({permissions:[]});});
    assert.equal(noPermission.out.state,'runtime_incomplete');tests++;
    const listenerFalse=await run(s=>{s.chrome.runtime.getBackgroundPage=cb=>cb({chrome:{webRequest:{onBeforeRequest:{hasListeners:()=>false}}}});});
    assert.equal(listenerFalse.out.state,'runtime_incomplete');assert.equal(listenerFalse.out.requestListenerRegistered,false);tests++;
    const noResponse=await run(s=>{s.vAPI.messaging.send=()=>new Promise(()=>{});},2000);
    assert.equal(noResponse.out.state,'probe_failed');assert.equal(noResponse.out.stage,'getAppData');assert.match(noResponse.out.error,/timed out/);tests++;
    const invalidApp=await run(s=>{s.vAPI.messaging.send=()=>Promise.resolve(undefined);});
    assert.equal(invalidApp.out.state,'background_no_reply');tests++;
    const throwingOptional=await run(s=>{s.chrome.runtime.getBackgroundPage=()=>{throw Error('Unsupported API');};});
    assert.equal(throwingOptional.out.state,'runtime_ready');tests++;
    const errorDocument=await run(s=>{
        delete s.vAPI;delete s.chrome;
        s.document={readyState:'complete',querySelector(selector){return selector.includes('main-frame-error')?{}:null;}};
    });
    assert.equal(errorDocument.out.state,'extension_page_blocked');assert.equal(errorDocument.out.requestBlockingVerified,false);tests++;
    const missingBindings=await run(s=>{
        delete s.vAPI;delete s.chrome;
        s.document={readyState:'complete',querySelector(selector){return selector.includes('vapi-client')?{}:null;}};
    });
    assert.equal(missingBindings.out.state,'unsupported_extension_messaging');assert.equal(missingBindings.out.documentContext.hasUboClientScript,true);tests++;
    const pollScript=fs.readFileSync(path.join(__dirname,'../assets/ubo-poll.js'),'utf8');
    const poll=s=>JSON.parse(vm.runInNewContext(pollScript,s));
    const ownLocation={protocol:'chrome-extension:',hostname:'fkgkibajhfbepljeaefdnfnegdcjomkh'};
    assert.equal(poll({location:ownLocation}).state,'probe_context_lost');tests++;
    assert.equal(poll({location:ownLocation,__internetPrivateUboProbe_v1:{state:'pending',probeVersion:'0.4.2'}}).state,'probe_context_lost');tests++;
    assert.equal(poll({location:ownLocation,__internetPrivateUboProbe_v1:{state:'pending',probeVersion:'0.7.4'}}).state,'pending');tests++;
    assert.equal(poll({location:{protocol:'chrome-error:',hostname:'chromewebdata'},document:{querySelector:()=>({})}}).state,'extension_page_blocked');tests++;
    assert.equal(poll({location:{protocol:'https:',hostname:'example.com'}}).state,'probe_origin_changed');tests++;
    const blockedOnEntry=await run(s=>{s.location={protocol:'chrome-error:',hostname:'chromewebdata'};s.document={querySelector:()=>({})};});
    assert.equal(blockedOnEntry.out.state,'extension_page_blocked');assert.deepEqual(blockedOnEntry.requests,[]);tests++;
    for(const name of Object.keys(reported.out))assert.ok(!/url|history|title|tab/i.test(name));
    console.log('PASS: '+tests+' scenarios, including missing getBackgroundPage, raw-port fallback, partial chrome/browser APIs, failures and honest unknown listener/blocking status');
})().catch(e=>{console.error(e);process.exitCode=1;});
