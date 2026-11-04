// Executes the ACTUAL bundled uBO background. Browser APIs/DOM/storage are a test
// harness, so this tests full engine startup/matching, not Samsung native cancellation.
const fs=require('fs'),path=require('path'),vm=require('vm');
const deps=process.env.REFERENCE_DEPS||path.resolve(__dirname,'../../compat/node_modules');
(async()=>{
const {Window}=await import(path.join(deps,'happy-dom/lib/index.js'));
const fidb=require(path.join(deps,'fake-indexeddb'));
const ext=path.resolve(process.argv[2]||'analysis/reference-candidate');const manifest=JSON.parse(fs.readFileSync(path.join(ext,'manifest.json')));const hash=manifest.key?require('crypto').createHash('sha256').update(Buffer.from(manifest.key,'base64')).digest().subarray(0,16):null;const ID=hash?Array.from(hash).map(v=>String.fromCharCode(97+(v>>4),97+(v&15))).join(''):'fkgkibajhfbepljeaefdnfnegdcjomkh',origin=`chrome-extension://${ID}/`;
const w=new Window({url:origin+'background.html'}),store=process.env.TEST_STORE&&fs.existsSync(process.env.TEST_STORE)?JSON.parse(fs.readFileSync(process.env.TEST_STORE)):{};
fs.writeFileSync(path.join(ext,'package.json'),JSON.stringify({type:'module'}));
for(const key of ['document','HTMLDocument','XMLDocument','HTMLDivElement','Element','DOMParser','Event','CustomEvent','CSSStyleSheet','CSS','Image','MutationObserver','Node','DOMRect','XMLHttpRequest','FileReader'])globalThis[key]=w[key];
globalThis.self=globalThis;globalThis.window=globalThis;globalThis.location=w.location;
if(process.env.FROZEN_NATIVE_DEADLINES==='1'){const nativeTimer=globalThis.setTimeout;globalThis.setTimeout=(fn,ms,...args)=>nativeTimer(fn,ms===2000||ms===15000?120000:ms,...args);}
Object.defineProperty(globalThis,'navigator',{value:{userAgent:'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/137.0.0.0 Mobile Safari/537.36',language:'en-US',languages:['en-US'],hardwareConcurrency:4},configurable:true});
globalThis.indexedDB=fidb.indexedDB;globalThis.IDBKeyRange=fidb.IDBKeyRange;
globalThis.addEventListener=w.addEventListener.bind(w);globalThis.dispatchEvent=w.dispatchEvent.bind(w);globalThis.removeEventListener=w.removeEventListener.bind(w);
globalThis.requestIdleCallback=fn=>setTimeout(()=>fn({timeRemaining:()=>20,didTimeout:false}),1);globalThis.cancelIdleCallback=clearTimeout;
globalThis.requestAnimationFrame=fn=>setTimeout(fn,1);globalThis.cancelAnimationFrame=clearTimeout;
const events=new Map();function event(name){if(events.has(name))return events.get(name);const listeners=[],filters=new Map();const e={filters,addListener(fn,filter){listeners.push(fn);filters.set(fn,filter)},removeListener(fn){let i=listeners.indexOf(fn);if(i>=0)listeners.splice(i,1)},hasListeners(){return listeners.length>0},listeners};events.set(name,e);return e;}
function generic(name){return new Proxy({}, {get(obj,key){if(key in obj)return obj[key];if(typeof key!=='string')return undefined;return obj[key]=key.startsWith('on')?event(name+'.'+key):(...args)=>{const cb=args.at(-1);if(typeof cb==='function')setImmediate(()=>cb());};}});}
function storage(){return {get(keys,cb){if(process.env.MULTI_KEY_READ_HANG==='1'&&Array.isArray(keys)&&keys.length>1)return;if(process.env.STRING_OVERLOAD_HANG==='1'&&typeof keys==='string')return;if(typeof keys==='function'){cb=keys;keys=null;}let result={};if(keys==null)result={...store};else if(typeof keys==='string'){if(Object.hasOwn(store,keys))result[keys]=store[keys];}else if(Array.isArray(keys)){for(const k of keys)if(Object.hasOwn(store,k))result[k]=store[k];}else{result={...keys};for(const k in keys)if(Object.hasOwn(store,k))result[k]=store[k];}setImmediate(()=>cb(structuredClone(result)));},set(data,cb){const copy=structuredClone(data);if(process.env.DELAY_VERSION_COMMIT==='1'&&Object.hasOwn(copy,'version')){setTimeout(()=>Object.assign(store,copy),5000);}else Object.assign(store,copy);setImmediate(()=>cb?.());},remove(keys,cb){for(const k of Array.isArray(keys)?keys:[keys])delete store[k];setImmediate(()=>cb?.());},clear(cb){for(const k in store)delete store[k];setImmediate(()=>cb?.());},getBytesInUse(keys,cb){cb(0);}};}
if(manifest.name.startsWith('__MSG'))manifest.name='uBlock Origin';
const messages=JSON.parse(fs.readFileSync(path.join(ext,'_locales/en/messages.json')));
const chrome={runtime:{id:ID,getManifest:()=>manifest,getURL:p=>origin+p,getPlatformInfo:cb=>cb({os:'android',arch:'arm64'}),getBackgroundPage:cb=>cb(globalThis),reload(){if(process.env.TEST_STORE){fs.writeFileSync(process.env.TEST_STORE,JSON.stringify(store));console.log('FIRST_INSTALL_RESTART');process.exit(42);}throw Error('Unexpected extension restart')},onConnect:event('runtime.onConnect'),onMessage:event('runtime.onMessage'),onInstalled:event('runtime.onInstalled'),onStartup:event('runtime.onStartup'),onUpdateAvailable:event('runtime.onUpdateAvailable'),lastError:undefined},
 i18n:{getMessage:k=>messages[k]?.message||k,getUILanguage:()=> 'en-US'},extension:{inIncognitoContext:false,isAllowedIncognitoAccess:cb=>cb(true)},
 storage:{local:storage(),managed:{get(){/* The exact unresponsive API reported by Samsung. */}},sync:storage(),onChanged:event('storage.onChanged')},
 tabs:Object.assign(generic('tabs'),{query:(query,cb)=>cb([])}),windows:generic('windows'),alarms:generic('alarms'),browserAction:generic('browserAction'),webRequest:generic('webRequest'),webNavigation:generic('webNavigation'),contextMenus:generic('contextMenus'),commands:generic('commands'),permissions:generic('permissions'),privacy:{network:{networkPredictionEnabled:generic('privacy'),webRTCIPHandlingPolicy:generic('privacy')},websites:{hyperlinkAuditingEnabled:generic('privacy')}},management:generic('management')};
let chromeSettingCalls=0,privacyReads=0;
if(process.env.TEST_CHROMESETTING_ABORT==='1'){
 for(const [category,names] of [['network',['networkPredictionEnabled','webRTCIPHandlingPolicy']],['websites',['hyperlinkAuditingEnabled']]]){
  for(const name of names)chrome.privacy[category][name]=Object.fromEntries(['get','set','clear'].map(method=>[method,()=>{chromeSettingCalls++;console.error('NATIVE_CHROMESETTING_ABORT types.ChromeSetting.'+method);process.exit(86);} ]));
 }
}
const privacyNamespace=chrome.privacy;
Object.defineProperty(chrome,'privacy',{get(){privacyReads++;if(process.env.TEST_FORBID_PRIVACY_READ==='1'){console.error('FORBIDDEN_PRIVACY_NAMESPACE_ACCESS');process.exit(87);}return privacyNamespace;}});

function promiseStorage(area){const output={...area};for(const name of ['get','set','remove','clear','getBytesInUse']){const fn=area[name];output[name]=function(...args){const cb=typeof args.at(-1)==='function'?args.pop():null;return new Promise((resolve,reject)=>{try{fn.call(area,...args,(...values)=>{if(process.env.API_RETURN_STYLE==='hybrid'&&cb)cb(...values);resolve(values[0]);});}catch(e){reject(e);}});};}return output;}
if(process.env.API_RETURN_STYLE==='promise'||process.env.API_RETURN_STYLE==='hybrid')chrome.storage.local=promiseStorage(chrome.storage.local);
chrome.webRequest.ResourceType={WEBSOCKET:'websocket'};globalThis.chrome=chrome;globalThis.browser=process.env.SPLIT_NAMESPACE==='1'?{...chrome,storage:{...chrome.storage,local:promiseStorage(chrome.storage.local)}}:chrome;
class AssetXHR {
 constructor(){this.handlers={};this.status=0;this.responseText='';this.response='';}
 addEventListener(name,fn){(this.handlers[name]||=[]).push(fn);}
 removeEventListener(name,fn){this.handlers[name]=(this.handlers[name]||[]).filter(f=>f!==fn);}
 open(method,url){this.url=url;}
 overrideMimeType(){} setRequestHeader(){} getResponseHeader(){return 'text/plain';}
 send(){setImmediate(()=>{if(this.aborted)return;try{if(!this.url.startsWith(origin))throw Error('Offline reference test');const p=new URL(this.url).pathname.slice(1);this.responseText=fs.readFileSync(path.join(ext,p),'utf8');this.response=this.responseText;this.status=200;this.statusText='OK';this.responseURL=this.url;this.readyState=4;this.emit('load');}catch{this.status=0;this.emit('error');}});}
 abort(){this.aborted=true;} emit(name){const ev={target:this};this['on'+name]?.(ev);for(const fn of this.handlers[name]||[])fn.call(this,ev);}
}
globalThis.XMLHttpRequest=AssetXHR;
globalThis.fetch=async(input,options)=>{let url=typeof input==='string'?input:input.url;if(url.startsWith(origin)){let p=decodeURIComponent(new URL(url).pathname).slice(1);try{const data=fs.readFileSync(path.join(ext,p));return new Response(data,{status:200,headers:{'content-type':p.endsWith('.wasm')?'application/wasm':'text/plain'}});}catch{return new Response('',{status:404});}}return new Response('',{status:503});};
if(fs.existsSync(path.join(ext,'js/internet-tools-diagnostics.js'))){globalThis.localStorage=w.localStorage;vm.runInThisContext(fs.readFileSync(path.join(ext,'js/internet-tools-diagnostics.js'),'utf8'),{filename:'internet-tools-diagnostics.js'});}
vm.runInThisContext(fs.readFileSync(path.join(ext,'js/vapi.js'),'utf8'),{filename:'vapi.js'});
if(fs.existsSync(path.join(ext,'js/scriptlets-compat.js')))vm.runInThisContext(fs.readFileSync(path.join(ext,'js/scriptlets-compat.js'),'utf8'),{filename:'scriptlets-compat.js'});
vm.runInThisContext(fs.readFileSync(path.join(ext,'js/start-compat.js'),'utf8'),{filename:path.join(ext,'js/start-compat.js'),importModuleDynamically:spec=>import(require('url').pathToFileURL(path.join(ext,spec.replace(/^\//,''))).href)});
if(globalThis.__internetToolsEngine){
 const create=chrome.tabs.create;let requested;
 chrome.tabs.create=(info,cb)=>{requested=info;cb({...info,id:909});};
 const dashboard=await __internetToolsEngine.openDashboard();
 require('assert/strict').equal(dashboard.requested,true);require('assert/strict').equal(requested.url,origin+'dashboard.html');require('assert/strict').equal(requested.active,true);
 chrome.tabs.create=create;console.log('PASS: dashboard creation is independent of pending filtering startup');
}
const deadline=Date.now()+Number(process.env.TEST_TIMEOUT||20000);while(Date.now()<deadline&&!globalThis.µBlock?.readyToFilter)await new Promise(r=>setTimeout(r,100));
const core=globalThis.µBlock;console.log(JSON.stringify({core:!!core,ready:core?.readyToFilter,startup:globalThis.__internetToolsStartup,stats:core?.supportStats,selectedLists:core?.selectedFilterLists,networkFilters:globalThis.__internetToolsEngine?.getNetworkFilterCount()},null,2));
if(!core?.readyToFilter)throw Error('Full uBO did not initialize with unresponsive managed storage');
if(globalThis.__internetToolsEngine.getNetworkFilterCount()<10000)throw Error('Default bundled filter lists did not load');
const assert=require('assert/strict');
if(process.env.TEST_VERIFY_UNSUPPORTED_SETTINGS==='1'){
 assert.equal(core.privacySettingsSupported,false);
 for(const name of ['prefetchingDisabled','hyperlinkAuditingDisabled','webrtcIPAddressHidden']){
  const before=core.userSettings[name];core.changeUserSettings(name,!before);core.changeUserSettings(name,before);assert.equal(core.userSettings[name],before);
 }
 assert.equal(chromeSettingCalls,0);assert.equal(privacyReads,0);
 console.log('PASS: zero native ChromeSetting calls or privacy namespace reads during full startup and all three setting changes; capabilities report unsupported');
}
await core.isReadyPromise;await new Promise(r=>setTimeout(r,100));
const page='https://internet-tools.test/';
chrome.tabs.get=(id,cb)=>cb({id,url:page});
core.tabContextManager.commit(101,page);core.bindTabToPageStore(101,'tabCommitted',{id:101,url:page});
function request(url,n,tabId=101,documentUrl=page){const details={requestId:'reference-'+n,tabId,frameId:0,parentFrameId:-1,type:'script',url,initiator:documentUrl,documentUrl,method:'GET',timeStamp:Date.now()};let result;for(const fn of event('webRequest.onBeforeRequest').listeners){const filter=event('webRequest.onBeforeRequest').filters.get(fn);if(filter?.types&&!filter.types.includes(details.type))continue;if(filter?.urls&&!filter.urls.some(pattern=>pattern==='<all_urls>'||new RegExp('^'+pattern.split('*').map(part=>part.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('.*')+'$').test(url)))continue;const value=fn(details);if(value!==undefined)result=value;}return result;}
const blocked=request('https://googleads.g.doubleclick.net/pagead/id',1);console.log('AD REQUEST',JSON.stringify(blocked));assert.equal(blocked?.cancel,true);
const allowed=request('https://internet-tools.test/reference-control.js',2);console.log('CONTROL REQUEST',JSON.stringify(allowed));assert.notEqual(allowed?.cancel,true);
console.log('PASS: original traffic.js listener cancels a default-list ad request and allows the control request');
const nonce='a0123456789abcdef01234567',testOrigin='http://127.0.0.1:4444';let synthetic;
chrome.tabs.create=(info,cb)=>{synthetic={...info,id:202};chrome.tabs.get=(id,cb)=>cb(id===202?synthetic:{id,url:page});core.tabContextManager.commit(202,info.url);core.bindTabToPageStore(202,'tabCommitted',synthetic);cb(synthetic);};
chrome.tabs.remove=(id,cb)=>cb();
const before=(await core.loadUserFilters()).content;
await __internetToolsEngine.beginTest(testOrigin,nonce);
assert.equal(request(testOrigin+'/'+nonce+'/blocked.js',3,202,synthetic.url)?.cancel,true);
assert.notEqual(request(testOrigin+'/'+nonce+'/control.js',4,202,synthetic.url)?.cancel,true);
const content=await new Promise(resolve=>vAPI.messaging.listeners.get('contentscript').fn({what:'retrieveContentScriptParameters',url:synthetic.url,needScriptlets:true},{tabId:202,frameId:0,url:synthetic.url},resolve));
assert.ok(content.specificCosmeticFilters);assert.ok(JSON.stringify(content.specificCosmeticFilters).includes('it'+nonce));console.log('PASS: original cosmetic engine returns the synthetic selector');
const finish=await __internetToolsEngine.finishTest(nonce);assert.equal(finish.blockedByUbo,true);assert.equal(finish.cleanupVerified,true);assert.equal(finish.testTabClosed,true);
assert.equal((await core.loadUserFilters()).content.trim(),before.trim());
console.log('PASS: synthetic test rule blocks only its target; original user filters are restored');
store.referenceSetting='user';const defaults=await vAPI.storage.get({referenceSetting:'fallback',referenceMissing:'default'});assert.equal(defaults.referenceSetting,'user');assert.equal(defaults.referenceMissing,'default');assert.equal((await vAPI.storage.get('referenceSetting')).referenceSetting,'user');await vAPI.storage.remove('referenceSetting');assert.equal(store.referenceSetting,undefined);
console.log('PASS: real uBO background and bundled filtering engine initialized despite managed API timeout');if(process.env.TEST_STORE)fs.writeFileSync(process.env.TEST_STORE,JSON.stringify(store));process.exit(0);
})().catch(e=>{console.error(e);process.exit(1)});
