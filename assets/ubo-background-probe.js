(async()=>{
    const ID='odkabbfgkiaeajgdfcfhnbkmjjkolehf';
    const out={state:'runtime_incomplete',probeVersion:'1.0.1',requestBlockingVerified:false};
    try{
        const api=[globalThis.chrome,globalThis.browser].find(a=>a&&a.runtime&&typeof a.runtime.getManifest==='function');
        if(!api)return {...out,state:'background_bindings_unavailable'};
        let bg=globalThis;
        const directBackground=!!globalThis.µBlock||
            (globalThis.location?.protocol==='chrome-extension:'&&globalThis.location?.hostname===ID&&globalThis.location?.pathname==='/background.html');
        if(!directBackground&&typeof api.runtime.getBackgroundPage==='function'){
            const returned=await new Promise(resolve=>{let done=false;const finish=value=>{if(done)return;done=true;clearTimeout(timer);resolve(value||null);};const timer=setTimeout(()=>finish(null),1500);try{const value=api.runtime.getBackgroundPage(finish);if(value&&typeof value.then==='function')value.then(finish,()=>finish(null));}catch{try{Promise.resolve(api.runtime.getBackgroundPage()).then(finish,()=>finish(null));}catch{finish(null);}}});
            if(returned)bg=returned;
        }
        const native=[bg.chrome,bg.browser,api].find(a=>a&&a.runtime&&typeof a.runtime.getManifest==='function');
        const manifest=native.runtime.getManifest();out.version=manifest.version;
        const ownLocation=object=>{try{return object.location.protocol==='chrome-extension:'&&object.location.hostname===ID;}catch{return false;}};
        if(typeof native.runtime.id==='string'&&native.runtime.id!==ID)return {...out,state:'unexpected_extension_build'};
        if(native.runtime.id!==ID&&!ownLocation(bg)&&!ownLocation(globalThis))return {...out,state:'unexpected_background_target'};
        if(manifest.version!=='1.75.0.7')return {...out,state:'unexpected_extension_build',expectedVersion:'1.75.0.7'};
        globalThis.__internetToolsRuntime=bg;
        if(bg.__internetToolsDiagnostics)out.diagnostics=bg.__internetToolsDiagnostics.snapshot();
        out.backgroundAccess=directBackground?'direct':'getBackgroundPage';
        const core=bg.µBlock;out.startup={backgroundAccessible:true,corePresent:!!core,documentReadyState:bg.document?.readyState||'unknown'};
        out.incognitoContext=native.extension?.inIncognitoContext??null;
        if(out.incognitoContext===true)return {...out,state:'private_background_skipped'};
        out.dashboardAvailable=typeof bg.__internetToolsEngine?.openDashboard==='function';
        if(!core)return {...out,state:'background_core_missing'};
        globalThis.__internetToolsRuntime=bg;
        out.privacySettingsSupported=core.privacySettingsSupported===true;
        out.nativeChromeSettingCallsDisabled=core.privacySettingsSupported===false;
        out.readyToFilter=core.readyToFilter===true;
        out.startup.selectedListCount=core.selectedFilterLists?.length||0;out.startup.supportStats=core.supportStats;
        if(bg.__internetToolsInstance)out.startup.instance=bg.__internetToolsInstance;
        if(bg.__internetToolsStartup){out.startup.phase=bg.__internetToolsStartup.phase;out.startup.phaseAgeMs=Math.max(0,Date.now()-bg.__internetToolsStartup.since);}
        if(bg.__internetToolsApi)out.startup.api=bg.__internetToolsApi;
        if(bg.__internetToolsPendingNative)out.startup.pendingNative=Object.entries(bg.__internetToolsPendingNative).map(([id,x])=>({id:Number(id),api:x.api,ageMs:Math.max(0,Date.now()-x.since)}));
        if(!out.readyToFilter)return out;
        out.initializationComplete=await Promise.race([core.isReadyPromise.then(()=>true),new Promise(resolve=>setTimeout(()=>resolve(false),1000))]);
        const engine=bg.__internetToolsEngine;out.networkFilterCount=engine?engine.getNetworkFilterCount():0;
        out.requestListenerRegistered=null;try{out.requestListenerRegistered=native.webRequest.onBeforeRequest.hasListeners();}catch{}
        out.state=out.initializationComplete&&out.networkFilterCount>0&&out.requestListenerRegistered!==false?'runtime_ready':'runtime_incomplete';return out;
    }catch{return {...out,state:'background_probe_failed'};}
})()
