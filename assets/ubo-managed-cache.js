(() => {
    'use strict';
    const key = '__internetToolsManagedRepair_v1';
    if (location.protocol !== 'chrome-extension:' || location.hostname !== 'fkgkibajhfbepljeaefdnfnegdcjomkh') return 'wrong_origin';
    const out = globalThis[key] = {state:'pending', repairVersion:'0.7.4', changed:false};
    const markerKey = 'internetToolsManagedCacheFix';
    const undo = globalThis.__internetToolsRepairMode === 'undo';
    const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
    const finish = (state, error) => { out.state=state; if(error)out.error=String(error).slice(0,300); };
    const api = [globalThis.chrome,globalThis.browser].find(a=>a&&a.runtime&&typeof a.runtime.getBackgroundPage==='function');
    const call = (runtime, area, method, argument) => new Promise((resolve,reject)=>{
        let settled=false;
        const timer=setTimeout(()=>{if(settled)return;settled=true;reject(new Error(method+'_callback_timeout'));},2000);
        try {
            area[method](argument, value=>{
                if(settled)return;settled=true;clearTimeout(timer);
                if(runtime.lastError)return reject(new Error(method+'_api_error'));
                resolve(value);
            });
        } catch { if(!settled){settled=true;clearTimeout(timer);reject(new Error(method+'_threw'));} }
    });
    const background = () => new Promise((resolve,reject)=>{
        let settled=false;
        const timer=setTimeout(()=>{if(settled)return;settled=true;reject(new Error('background_callback_timeout'));},2000);
        if(!api){clearTimeout(timer);return reject(new Error('background_api_unavailable'));}
        try { api.runtime.getBackgroundPage(bg=>{if(settled)return;settled=true;clearTimeout(timer);if(!bg)return reject(new Error('background_unavailable'));resolve(bg);}); }
        catch {settled=true;clearTimeout(timer);reject(new Error('background_api_threw'));}
    });
    (async()=>{
        try {
            const bg=await background();
            const native=[bg.chrome,bg.browser].find(a=>a&&a.runtime&&a.storage&&a.storage.local&&typeof a.storage.local.get==='function');
            if(!native || typeof native.runtime.getManifest!=='function')throw new Error('background_bindings_unavailable');
            const manifest=native.runtime.getManifest();
            if((typeof native.runtime.id==='string'&&native.runtime.id!=='fkgkibajhfbepljeaefdnfnegdcjomkh')||!manifest||manifest.name!=='uBlock Origin'||manifest.version!=='1.75.0')throw new Error('unexpected_extension');
            const local=native.storage.local;
            const keys=['cachedManagedStorage',markerKey];
            let existing=await call(native.runtime,local,'get',keys)||{};
            if(undo){
                const marker=existing[markerKey], cache=existing.cachedManagedStorage;
                if(!object(marker)||marker.schema!==1||marker.createdMissingCache!==true){finish('startup_fix_not_owned');return;}
                if(!object(cache)||Object.keys(cache).length!==0){finish('startup_fix_cache_changed','Cache is no longer empty; it was left untouched.');return;}
                let removeCallback = 'responded';
                try { await call(native.runtime,local,'remove',keys); } catch { removeCallback = 'unconfirmed'; }
                const after=await call(native.runtime,local,'get',keys)||{};
                if(Object.hasOwn(after,'cachedManagedStorage')||Object.hasOwn(after,markerKey))throw new Error('undo_readback_failed');
                out.removeCallback=removeCallback;out.changed=true;finish('startup_fix_removed');return;
            }
            const core=bg.µBlock;
            if(!core||core.readyToFilter!==false||!Array.isArray(core.selectedFilterLists)||core.selectedFilterLists.length!==0
                ||(core.supportStats&&Object.hasOwn(core.supportStats,'cacheBackend'))){finish('startup_fix_not_applicable');return;}
            if(Object.hasOwn(existing,'cachedManagedStorage')){
                finish(object(existing.cachedManagedStorage)?'managed_cache_already_present':'startup_fix_invalid_cache');return;
            }
            if(!native.storage.managed||typeof native.storage.managed.get!=='function'){finish('startup_fix_not_applicable');return;}
            // Re-check the actual API. Cache returned policies when possible, exactly as upstream does.
            const observed=await new Promise(resolve=>{
                let done=false;
                const timer=setTimeout(()=>{if(done)return;done=true;resolve({source:'managed_timeout',value:{}});},3000);
                try { native.storage.managed.get(null, value=>{
                    if(done)return;done=true;clearTimeout(timer);
                    resolve({source:native.runtime.lastError?'managed_error':'managed_response',value:native.runtime.lastError?{}:(object(value)?value:{})});
                }); } catch {if(!done){done=true;clearTimeout(timer);resolve({source:'managed_threw',value:{}});}}
            });
            existing=await call(native.runtime,local,'get',keys)||{};
            if(Object.hasOwn(existing,'cachedManagedStorage')){finish('managed_cache_already_present');return;}
            if(core.readyToFilter!==false||core.selectedFilterLists.length!==0
                ||(core.supportStats&&Object.hasOwn(core.supportStats,'cacheBackend'))){finish('startup_fix_not_applicable');return;}
            const marker={schema:1,createdMissingCache:true};
            out.stage='write_cache';out.source=observed.source;
            let writeCallback = 'responded';
            try { await call(native.runtime,local,'set',{cachedManagedStorage:observed.value,[markerKey]:marker}); }
            catch { writeCallback = 'unconfirmed'; }
            const verified=await call(native.runtime,local,'get',keys)||{};
            if(!object(verified.cachedManagedStorage)||!object(verified[markerKey])||verified[markerKey].schema!==1||verified[markerKey].createdMissingCache!==true)throw new Error('cache_readback_failed');
            out.writeCallback=writeCallback;out.changed=true;out.stage='cache_verified';finish('managed_cache_initialized');
        } catch(error){finish('startup_fix_failed',error&&error.message||error);}
    })();
    return 'repair_started';
})()
