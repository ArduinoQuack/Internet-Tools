const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const script=fs.readFileSync(path.join(__dirname,'../assets/ubo-managed-cache.js'),'utf8');
const ID='fkgkibajhfbepljeaefdnfnegdcjomkh';
async function scenario(options={}){
    const timers=[],writes=[],removed=[];let getCount=0;
    const store=structuredClone(options.store||{});
    const runtime={id:options.id||ID,getManifest(){return {name:'uBlock Origin',version:options.version||'1.75.0'};}};
    if(options.missingId)delete runtime.id;
    const native={runtime,storage:{local:{
        get(keys,cb){if(options.noLocalCallback)return;getCount++;if(options.concurrentCache&&getCount===2)store.cachedManagedStorage=structuredClone(options.concurrentCache);const out={};for(const k of keys)if(Object.hasOwn(store,k))out[k]=structuredClone(store[k]);cb(out);},
        set(data,cb){writes.push(structuredClone(data));if(!options.dropWrite)Object.assign(store,structuredClone(data));if(!options.noSetCallback)cb();},
        remove(keys,cb){removed.push([...keys]);for(const k of keys)delete store[k];if(!options.noRemoveCallback)cb();}
    },managed:{get(key,cb){assert.equal(key,null);if(options.advanceCore)bg.µBlock.supportStats.cacheBackend='indexedDB';if(options.managedReply)cb(structuredClone(options.managedReply));}}}};
    const bg={chrome:native,µBlock:{readyToFilter:options.ready===true,selectedFilterLists:[],supportStats:{}}};
    const sandbox={location:{protocol:'chrome-extension:',hostname:ID},__internetToolsRepairMode:options.undo?'undo':'repair',
        chrome:{runtime:{getBackgroundPage:cb=>cb(bg)}},setTimeout(fn,ms){const t={fn,ms,cancelled:false};timers.push(t);return t;},clearTimeout(t){if(t)t.cancelled=true;}};
    const ctx=vm.createContext(sandbox);assert.equal(vm.runInContext(script,ctx),'repair_started');
    const settle=async()=>{for(let i=0;i<100;i++)await Promise.resolve();};
    await settle();
    for(const ms of [3000,2000]){
        const active=timers.filter(t=>t.ms===ms&&!t.cancelled&&!t.fired);
        for(const t of active){t.fired=true;t.fn();}
        await settle();
    }
    return {out:sandbox.__internetToolsManagedRepair_v1,store,writes,removed};
}
(async()=>{
    let tests=0;
    const user={userSettings:{autoUpdate:true},userFilters:'private rules',selectedFilterLists:['my-list']};
    const fixed=await scenario({store:user});
    assert.equal(fixed.out.state,'managed_cache_initialized');assert.equal(fixed.out.source,'managed_timeout');
    assert.equal(fixed.out.changed,true);assert.deepEqual(fixed.store.cachedManagedStorage,{});
    for(const key of Object.keys(user))assert.deepEqual(fixed.store[key],user[key]);
    assert.deepEqual(Object.keys(fixed.writes[0]).sort(),['cachedManagedStorage','internetToolsManagedCacheFix']);tests++;
    const preserved=await scenario({store:{...user,cachedManagedStorage:{adminSettings:{policy:'keep'}}}});
    assert.equal(preserved.out.state,'managed_cache_already_present');assert.equal(preserved.writes.length,0);tests++;
    const emptyExisting=await scenario({store:{cachedManagedStorage:{}}});
    assert.equal(emptyExisting.writes.length,0);tests++;
    const actualPolicy=await scenario({managedReply:{adminSettings:{autoUpdate:true}}});
    assert.equal(actualPolicy.out.source,'managed_response');assert.deepEqual(actualPolicy.store.cachedManagedStorage,{adminSettings:{autoUpdate:true}});tests++;
    const raced=await scenario({concurrentCache:{adminSettings:{policy:'arrived'}}});
    assert.equal(raced.out.state,'managed_cache_already_present');assert.equal(raced.writes.length,0);tests++;
    const dropped=await scenario({dropWrite:true});
    assert.equal(dropped.out.state,'startup_fix_failed');assert.equal(dropped.out.error,'cache_readback_failed');tests++;
    const advanced=await scenario({advanceCore:true});
    assert.equal(advanced.out.state,'startup_fix_not_applicable');assert.equal(advanced.writes.length,0);tests++;
    const absentId=await scenario({missingId:true});assert.equal(absentId.out.state,'managed_cache_initialized');tests++;
    const badId=await scenario({id:'another-extension'});assert.equal(badId.out.state,'startup_fix_failed');assert.equal(badId.writes.length,0);tests++;
    const badVersion=await scenario({version:'2.0.0'});assert.equal(badVersion.out.state,'startup_fix_failed');assert.equal(badVersion.writes.length,0);tests++;
    const ready=await scenario({ready:true});assert.equal(ready.out.state,'startup_fix_not_applicable');assert.equal(ready.writes.length,0);tests++;
    const localStall=await scenario({noLocalCallback:true});assert.equal(localStall.out.state,'startup_fix_failed');assert.equal(localStall.writes.length,0);tests++;
    const setStall=await scenario({noSetCallback:true});assert.equal(setStall.out.state,'managed_cache_initialized');assert.equal(setStall.out.writeCallback,'unconfirmed');assert.equal(setStall.out.stage,'cache_verified');tests++;
    const undo=await scenario({undo:true,store:fixed.store});assert.equal(undo.out.state,'startup_fix_removed');
    assert.deepEqual(undo.store,user);tests++;
    const undoNoCallback=await scenario({undo:true,noRemoveCallback:true,store:fixed.store});
    assert.equal(undoNoCallback.out.state,'startup_fix_removed');assert.deepEqual(undoNoCallback.store,user);tests++;
    const changed=await scenario({undo:true,store:{...fixed.store,cachedManagedStorage:{policy:'keep'}}});
    assert.equal(changed.out.state,'startup_fix_cache_changed');assert.equal(changed.removed.length,0);tests++;
    const notOurs=await scenario({undo:true,store:{cachedManagedStorage:{}}});assert.equal(notOurs.out.state,'startup_fix_not_owned');assert.equal(notOurs.removed.length,0);tests++;

    // Execute the ACTUAL unmodified upstream adminStorage function from the pinned source archive.
    const archive=path.join(__dirname,'../upstream/uBlock-1.75.0-source.zip');
    const source=execFileSync('python3',['-c',"import zipfile,sys\nwith zipfile.ZipFile(sys.argv[1]) as z:\n print(z.read(next(n for n in z.namelist() if n.endswith('/platform/common/vapi-background.js'))).decode())",archive],{encoding:'utf8'});
    const start=source.indexOf('vAPI.adminStorage = (( ) => {');
    const original=source.slice(start,source.indexOf('})();',start)+5);
    assert.ok(start>0&&original.includes("Object.keys(bin).length === 0"));
    function upstream(cache){
        const context=vm.createContext({snapshot:JSON.stringify(cache)});
        vm.runInContext(`globalThis.localStore=JSON.parse(snapshot);globalThis.vAPI={storage:{get:async key=>Object.hasOwn(localStore,key)?{[key]:localStore[key]}:{},set:async bin=>Object.assign(localStore,bin)}};globalThis.webext={storage:{managed:{get:()=>new Promise(()=>{})}}};`+original,context);
        return context;
    }
    const before=upstream({});let settled=false;
    vm.runInContext("vAPI.adminStorage.get(['adminSettings','toOverwrite'])",before).then(()=>settled=true);
    for(let i=0;i<100;i++)await Promise.resolve();assert.equal(settled,false);tests++;
    const after=upstream(fixed.store);
    const restored=await vm.runInContext("vAPI.adminStorage.get(['adminSettings','toOverwrite'])",after);
    assert.deepEqual(JSON.parse(JSON.stringify(restored)),{});
    assert.equal(await vm.runInContext("vAPI.adminStorage.get('toAdd')",after),undefined);tests++;
    const enterprise=upstream({cachedManagedStorage:{adminSettings:{policy:'keep'}}});
    const admin=await vm.runInContext("vAPI.adminStorage.get('adminSettings')",enterprise);
    assert.deepEqual(JSON.parse(JSON.stringify(admin)),{policy:'keep'});tests++;
    console.log(`PASS: ${tests} cache-repair scenarios, including the actual upstream function blocked before repair and resolving afterwards`);
})().catch(e=>{console.error(e);process.exitCode=1;});
