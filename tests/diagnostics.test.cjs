const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync(path.join(__dirname,'../compat/diagnostics.js'),'utf8');
(async()=>{
 const data=new Map();let now=10000;
 function boot(){const listeners={};const c={Date:{now:()=>now},Math,localStorage:{getItem:k=>data.get(k),setItem:(k,v)=>data.set(k,v)},addEventListener:(e,fn)=>listeners[e]=fn};vm.createContext(c);vm.runInContext(source,c);return {c,listeners,d:c.__internetToolsDiagnostics};}
 const first=boot();let complete;
 const operation=first.d.track('loadPublicSuffixList',()=>new Promise(r=>complete=r));now+=300;
 let snap=first.d.snapshot();assert.equal(snap.pending[0].name,'loadPublicSuffixList');assert.equal(snap.pending[0].ageMs,300);
 complete(7);assert.equal(await operation,7);assert.equal(first.d.snapshot().pending.length,0);
 await assert.rejects(first.d.track('loadFilterLists',async()=>{throw Object.assign(new Error('https://private.example/password=topsecret'),{name:'TypeError',stack:'TypeError: private content\n at chrome-extension://xxx/js/storage.js:123:45'});}));
 first.listeners.error({error:new TypeError('COOKIE=abc'),lineno:8});
 const serialized=JSON.stringify(first.d.snapshot());assert.ok(!serialized.includes('private.example'));assert.ok(!serialized.includes('topsecret'));assert.ok(!serialized.includes('COOKIE'));
 first.d.phase('loadFilteringEngines');now++;
 const second=boot();assert.equal(second.d.snapshot().previousBoots.at(-1).phase,'loadFilteringEngines');
 assert.ok(second.d.snapshot().previousBoots.at(-1).events.some(e=>e.event==='failed'));
 for(let i=0;i<100;i++)second.d.record('fixed_event');assert.equal(second.d.snapshot().events.length,32);
 for(let i=0;i<15;i++){now++;boot();}assert.equal(JSON.parse([...data.values()][0]).length,8);
 console.log('PASS: exact pending waits, rejected operations, bounded persistence across reloads and exclusion of error values/URLs');
})().catch(e=>{console.error(e);process.exitCode=1});
