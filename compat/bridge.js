// Runs inside the original background, against its actual traffic/filter engines.
const itTests = new Map();
import { invokeNative } from './internet-tools-api.js';
const itCall=(area,name,...args)=>invokeNative(area,name,args,{timeoutMs:5000});
const itClose = async saved => {
    if(typeof saved.tabId!=='number')return true;
    try{const tab=await itCall(chrome.tabs,'get',saved.tabId);if(!tab||tab.url!==saved.origin+'/'+saved.nonce+'/')return false;await itCall(chrome.tabs,'remove',saved.tabId);return true;}catch{return false;}
};
const itCleanup = async saved => {
    const current=await µb.loadUserFilters();
    if(current.error)throw new Error('self_test_cleanup_read_failed');
    if(current.content.includes(saved.block.trim())){
        const result=await µb.saveUserFilters(current.content.replace(saved.block.trim(),''));
        if(result&&result.error)throw new Error('self_test_cleanup_write_failed');
        await µb.loadFilterLists();
    }else if(current.content.includes('! Internet Tools self-test '+saved.nonce))throw new Error('self_test_block_changed');
    const verified=await µb.loadUserFilters();
    if(verified.error||verified.content.includes('! Internet Tools self-test '+saved.nonce))throw new Error('self_test_cleanup_unverified');
    await vAPI.storage.remove('internetToolsPendingSelfTest');
};
self.__internetToolsEngine=Object.freeze({
    getNetworkFilterCount:()=>staticNetFilteringEngine.getFilterCount(),
    async openDashboard(){const tab=await itCall(chrome.tabs,'create',{url:chrome.runtime.getURL('dashboard.html'),active:true});return {requested:!!tab};},
    async beginTest(origin,nonce){
        if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)||!/^a[0-9a-f]{24}$/.test(nonce))throw new Error('invalid_self_test');
        await µb.isReadyPromise;
        const pending=await vAPI.storage.get(['internetToolsPendingSelfTest']);
        if(pending&&pending.internetToolsPendingSelfTest){await itClose(pending.internetToolsPendingSelfTest);await itCleanup(pending.internetToolsPendingSelfTest);}
        const block='! Internet Tools self-test '+nonce+'\n||127.0.0.1:'+new URL(origin).port+'/'+nonce+'/blocked.js$script\n127.0.0.1##.it'+nonce+'\n127.0.0.1##+js(set-constant, it'+nonce+', true)\n! End Internet Tools self-test '+nonce;
        const saved={nonce,block,origin};await vAPI.storage.set({internetToolsPendingSelfTest:saved});
        await µb.appendUserFilters(block,{trustedSource:true});itTests.set(nonce,saved);
        try{const tab=await itCall(chrome.tabs,'create',{url:origin+'/'+nonce+'/',active:true});if(!tab||typeof tab.id!=='number')throw new Error('test_tab_unavailable');saved.tabId=tab.id;await vAPI.storage.set({internetToolsPendingSelfTest:saved});return {tabCreated:true};}
        catch(e){await itCleanup(saved);itTests.delete(nonce);throw e;}
    },
    async finishTest(nonce){
        const saved=itTests.get(nonce);if(!saved)throw new Error('test_context_lost');
        const store=µb.pageStoreFromTabId(saved.tabId);
        // The original journal aggregates request counts on a timer; flush only this synthetic tab.
        if(store&&typeof store.journalProcess==='function')store.journalProcess();
        const blocked=store?store.counts.blocked.any:0;
        let cleanup=false,closed=false;
        try{await itCleanup(saved);cleanup=true;}
        finally{closed=await itClose(saved);itTests.delete(nonce);}
        return {blockedByUbo:blocked>0,cleanupVerified:cleanup,testTabClosed:closed};
    }
});
