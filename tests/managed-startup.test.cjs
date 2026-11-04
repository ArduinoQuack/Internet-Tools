// Execute the shipped admin-storage implementation with native deadline timers
// deliberately not dispatched. No real admin data is discarded or fabricated.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync(path.resolve(process.argv[2],'js/vapi-background.js'),'utf8');
const begin=source.indexOf('vAPI.adminStorage = (( ) => {'),end=source.indexOf('\n})();',begin)+7;
assert.ok(begin>=0&&end>begin);const implementation=source.slice(begin,end);
async function fixture(cached,managed){
 const c=vm.createContext({initial:JSON.stringify(cached),managedValue:JSON.stringify(managed)});
 vm.runInContext(`const store=JSON.parse(initial);let writes=0,reads=0;const timerQueue=[];const setTimeout=fn=>{timerQueue.push(fn);return fn;};const clearTimeout=()=>{};const vAPI={storage:{get:async()=>({...store}),set:async bin=>{writes++;Object.assign(store,bin);}}};const webext={storage:{managed:{get:()=>{reads++;return managedValue===undefined?new Promise(()=>{}):Promise.resolve(JSON.parse(managedValue));}}}};`+implementation,c);
 return c;
}
async function read(c,key){c.keyJSON=JSON.stringify(key);vm.runInContext(`let settled=false,result;vAPI.adminStorage.get(JSON.parse(keyJSON)).then(value=>{settled=true;result=value;});`,c);for(let i=0;i<30;i++)await Promise.resolve();return {settled:vm.runInContext('settled',c),value:JSON.parse(vm.runInContext('JSON.stringify({value:result})',c)).value};}
(async()=>{
 let c=await fixture({},undefined),r=await read(c,['adminSettings','toOverwrite']);
 if(process.env.EXPECT_PENDING==='1'){assert.equal(r.settled,false);console.log('PASS: 0.7.1 remains blocked without its managed deadline timer');return;}
 assert.equal(r.settled,true);assert.deepEqual(r.value,{});assert.equal(vm.runInContext('writes',c),0);
 c=await fixture({cachedManagedStorage:{adminSettings:'saved',toOverwrite:{userSettings:['x']}}},undefined);r=await read(c,['adminSettings','toOverwrite']);assert.equal(r.settled,true);assert.equal(r.value.adminSettings,'saved');assert.deepEqual(r.value.toOverwrite,{userSettings:['x']});assert.equal(vm.runInContext('writes',c),0);
 c=await fixture({},undefined);r=await read(c,{adminSettings:'fallback'});assert.deepEqual(r.value,{adminSettings:'fallback'});
 c=await fixture({cachedManagedStorage:{adminSettings:'saved'}},undefined);r=await read(c,'adminSettings');assert.equal(r.value,'saved');
 c=await fixture({cachedManagedStorage:{adminSettings:'saved'}},{adminSettings:'actual native policy'});r=await read(c,null);assert.equal(r.value.adminSettings,'saved');assert.equal(vm.runInContext('store.cachedManagedStorage.adminSettings',c),'actual native policy');
 console.log('PASS: 5 cached-admin startup scenarios; pending native calls/timers never hold startup, and actual cached/native policy values are retained');
})().catch(e=>{console.error(e);process.exitCode=1});
