// Accept the two supported WebExtensions completion styles without inventing data.
let operationSerial=0;const pendingNative=globalThis.__internetToolsPendingNative=Object.create(null);
const apiStats=globalThis.__internetToolsApi=Object.create(null);
function label(area,name){
    try{
        for(const ns of [globalThis.chrome,globalThis.browser]){
            if(!ns)continue;
            for(const key of ['local','managed','sync'])if(area===ns.storage?.[key])return 'storage.'+key+'.'+name;
            for(const key of ['tabs','windows','alarms','browserAction','webNavigation','permissions'])if(area===ns[key])return key+'.'+name;
        }
    }catch{}
    return 'api.'+name;
}
function requiredStorageShape(area,name){return name==='get'&&label(area,name)==='storage.local.get';}
export function invokeNative(area,name,args,options={}){
    const key=label(area,name),s=apiStats[key]||(apiStats[key]={calls:0,callbacks:0,promises:0,pending:0,failures:0});s.calls++;s.pending++;const operationId=++operationSerial;pendingNative[operationId]={api:key,since:Date.now()};if(requiredStorageShape(area,name)){s.lastKeyCount=Array.isArray(args[0])?args[0].length:args[0]==null?null:1;}
    const transform=options.outFn||((v)=>v),requiredRead=/^storage\.(local|managed|sync)\.get$/.test(key);
    return new Promise((resolve,reject)=>{
        let settled=false,retried=false,timer;
        const finish=(error,values=[])=>{
            if(settled)return;settled=true;clearTimeout(timer);delete pendingNative[operationId];s.pending--;s.last=error?'failed':'completed';
            if(error){s.failures++;s.lastError=error?.name==='TypeError'?'TypeError':error?.message==='api_timeout'?'timeout':'Error';if(!options.noFail){reject(error);return;}}
            try{resolve(transform(...values));}catch(e){reject(e);}
        };
        const failure=error=>{
            // These errors report rejected argument validation, before an operation runs.
            if(!settled&&!retried&&/expected.*argument|too many arguments|no matching signature|does not accept.*callback/i.test(String(error?.message||error))){retried=true;call(false);return;}
            finish(error instanceof Error?error:new Error('native_api_failed'));
        };
        function call(withCallback){
            try{
                const callback=(...values)=>{s.callbacks++;const error=globalThis.chrome?.runtime?.lastError;finish(error?new Error('native_callback_failed'):null,values);};
                const returned=area[name](...args,...(withCallback?[callback]:[]));s.returnType=returned&&typeof returned.then==='function'?'promise':typeof returned;
                if(returned&&typeof returned.then==='function'){
                    s.promises++;
                    Promise.resolve(returned).then(value=>{
                        // A storage read must return a dictionary. Ignore an incidental
                        // void Promise from a callback implementation; wait for its data.
                        if(requiredRead&&value===undefined)return;
                        finish(null,[value]);
                    },failure);
                }
            }catch(error){failure(error);}
        }
        timer=setTimeout(()=>finish(new Error('api_timeout')),options.timeoutMs||15000);call(true);
    });
}

// Samsung's working single-key overload is used for every requested key. Merge
// only native values; a failed read rejects the entire operation.
export async function readLocalKeys(area,keys){
    if(!Array.isArray(keys))return invokeNative(area,'get',[keys]);
    const result={};
    for(const key of new Set(keys)){
        const bin=await invokeNative(area,'get',[[key]]);
        if(bin===null||typeof bin!=='object'||Array.isArray(bin))throw new Error('invalid_native_storage_read');
        if(Object.hasOwn(bin,key))Object.defineProperty(result,key,{value:bin[key],enumerable:true,writable:true,configurable:true});
    }
    return result;
}
