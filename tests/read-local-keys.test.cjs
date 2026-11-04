const assert=require('assert/strict'),path=require('path');
(async()=>{
 const {readLocalKeys}=await import(require('url').pathToFileURL(path.resolve(__dirname,'../compat/api-adapter.js')).href);
 const values={saved:3,off:false,empty:'',nil:null},calls=[];
 const area={get:(keys,cb)=>{calls.push(keys);assert.equal(keys.length,1);cb(Object.hasOwn(values,keys[0])?{[keys[0]]:values[keys[0]]}:{});}};globalThis.chrome={runtime:{},storage:{local:area}};
 assert.deepEqual(await readLocalKeys(area,['saved','missing','off','empty','nil','saved']),values);assert.equal(calls.length,5);
 assert.deepEqual(await readLocalKeys(area,[]),{});assert.equal(calls.length,5);
 area.get=keys=>Promise.resolve({[keys[0]]:values[keys[0]]});assert.deepEqual(await readLocalKeys(area,['saved','off']),{saved:3,off:false});
 area.get=(keys,cb)=>{if(keys[0]==='saved')cb({saved:3});else throw new Error('native read failed');};await assert.rejects(readLocalKeys(area,['saved','off']),/native read failed/);
 area.get=(keys,cb)=>cb(undefined);await assert.rejects(readLocalKeys(area,['saved']),/invalid_native_storage_read/);
 area.get=(keys,cb)=>cb(JSON.parse('{"__proto__":{"safe":true}}'));const result=await readLocalKeys(area,['__proto__']);assert.ok(Object.hasOwn(result,'__proto__'));assert.equal(Object.getPrototypeOf(result),Object.prototype);assert.deepEqual(result.__proto__,{safe:true});
 console.log('PASS: 6 single-key storage scenarios preserve native values, missing keys and read failures');
})().catch(e=>{console.error(e);process.exitCode=1});
