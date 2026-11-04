const assert=require('assert/strict'),path=require('path');
(async()=>{
const {invokeNative}=await import(require('url').pathToFileURL(path.resolve(__dirname,'../compat/api-adapter.js')).href);let n=0;
function area(get){const a={get};globalThis.chrome={runtime:{},storage:{local:a}};return a;}
assert.deepEqual(await invokeNative(area((keys,cb)=>cb({saved:'value'})),'get',[['saved']]),{saved:'value'});n++;
assert.deepEqual(await invokeNative(area(keys=>Promise.resolve({saved:'value'})),'get',[['saved']]),{saved:'value'});n++;
assert.deepEqual(await invokeNative(area((keys,cb)=>{cb({saved:'callback'});return Promise.reject(new Error('late failure'));}),'get',[['saved']]),{saved:'callback'});await new Promise(r=>setImmediate(r));n++;
assert.deepEqual(await invokeNative(area((keys,cb)=>{setTimeout(()=>cb({saved:'later'}),5);return Promise.resolve(undefined);}),'get',[['saved']],{timeoutMs:50}),{saved:'later'});n++;
let count=0;const strict=area(function(keys){count++;if(arguments.length>1)throw new TypeError('Expected at most 1 argument');return Promise.resolve({saved:'strict'});});assert.deepEqual(await invokeNative(strict,'get',[['saved']]),{saved:'strict'});assert.equal(count,2);n++;
await assert.rejects(invokeNative(area(()=>{}),'get',[['saved']],{timeoutMs:10}),/api_timeout/);n++;
await assert.rejects(invokeNative(area(()=>Promise.reject(new Error('Access denied'))),'get',[['saved']],{timeoutMs:30}),/Access denied/);n++;
const writes={set:()=>Promise.resolve()};globalThis.chrome.storage.local=writes;assert.equal(await invokeNative(writes,'set',[{saved:'real'}]),undefined);n++;
const browserLocal={get:(keys,cb)=>{setTimeout(()=>cb({saved:'browser'}),5);return Promise.resolve();}};globalThis.browser={storage:{local:browserLocal}};assert.deepEqual(await invokeNative(browserLocal,'get',[['saved']],{timeoutMs:50}),{saved:'browser'});delete globalThis.browser;n++;
console.log('PASS: '+n+' real API-adapter scenarios; callback/Promise results preserved and missing data never fabricated');
})().catch(e=>{console.error(e);process.exitCode=1});
