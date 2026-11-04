/* Local, bounded diagnostics. Never record storage values, requests or page URLs. */
(() => {
    const key='internetTools.lifecycle.v1', started=Date.now();
    const instance=started.toString(36)+'-'+Math.random().toString(36).slice(2);
    const events=[],pending=Object.create(null);let serial=0,history=[];
    try { const parsed=JSON.parse(localStorage.getItem(key)||'[]');if(Array.isArray(parsed))history=parsed.slice(-7); } catch {}
    const boot={instance,started,phase:'bootstrap',events:[]};history.push(boot);
    const save=()=>{try{localStorage.setItem(key,JSON.stringify(history));}catch{}};
    const record=(event,detail={})=>{
        const item={at:Date.now()-started,event,...detail};events.push(item);if(events.length>64)events.shift();
        boot.events=events.slice(-16);save();
    };
    const safeError=error=>{
        const name=String(error?.name||'Error');
        const stack=String(error?.stack||'');
        const frames=[...stack.matchAll(/(?:js\/)([a-zA-Z0-9_./-]+\.js):(\d+)(?::(\d+))?/g)].slice(0,5).map(m=>({source:m[1],line:Number(m[2]),column:Number(m[3]||0)}));
        return {name:/^[A-Za-z]{1,40}$/.test(name)?name:'Error',frames};
    };
    const phase=name=>{boot.phase=name;globalThis.__internetToolsStartup={phase:name,since:Date.now()};record('phase',{name});};
    const track=async(name,fn)=>{
        const id=++serial;pending[id]={name,since:Date.now()};record('begin',{id,name});
        try{const value=await fn();record('end',{id,name});return value;}
        catch(e){record('failed',{id,name,...safeError(e)});throw e;}
        finally{delete pending[id];}
    };
    globalThis.__internetToolsInstance=instance;
    globalThis.__internetToolsDiagnostics={record,phase,track,safeError,snapshot:()=>({instance,elapsedMs:Date.now()-started,pending:Object.entries(pending).map(([id,x])=>({id:Number(id),name:x.name,ageMs:Date.now()-x.since})),events:events.slice(-32),previousBoots:history.slice(0,-1)})};
    addEventListener('error',e=>record('error',{...safeError(e.error),line:Number(e.lineno)||0}));
    addEventListener('unhandledrejection',e=>record('unhandledrejection',safeError(e.reason)));
    addEventListener('pagehide',()=>record('pagehide'));
    addEventListener('beforeunload',()=>record('beforeunload'));
    record('boot');phase('bootstrap');
})();
