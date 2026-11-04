/* Rebuild the pinned uBO package with the Samsung startup compatibility change.
 * Requires esbuild 0.25.12. Original filtering/scriptlet implementation is retained. */
const fs=require('fs'),path=require('path'),cp=require('child_process');
const root=path.resolve(__dirname,'..'),work=path.resolve(process.argv[2]||path.join(root,'build','ubo-compat'));
const esbuild=require(process.env.ESBUILD_PACKAGE||'esbuild');
if(require('crypto').createHash('sha256').update(fs.readFileSync(path.join(root,'upstream/uBlock-1.75.0-original.crx'))).digest('hex')!=='d688ed3f0262ec4f8bdcd4c8f65987c5444c560aeb387a9e02aca1facdfa7046')throw Error('Pinned upstream CRX hash mismatch');
fs.rmSync(work,{recursive:true,force:true});fs.mkdirSync(work,{recursive:true});
cp.execFileSync('python3',['-c',`import zipfile,sys\nwith zipfile.ZipFile(sys.argv[1]) as z:z.extractall(sys.argv[2])`,path.join(root,'upstream','uBlock-1.75.0-original.crx'),work]);
fs.copyFileSync(path.join(root,'compat/api-adapter.js'),path.join(work,'js/internet-tools-api.js'));
const webextFile=path.join(work,'js/webext.js');let webextSource=fs.readFileSync(webextFile,'utf8');
const wrapperStart=webextSource.indexOf('const promisifyNoFail =');const wrapperEnd=webextSource.indexOf('const webext =',wrapperStart);
if(wrapperStart<0||wrapperEnd<0)throw Error('Pinned webext wrappers did not match');
webextSource=webextSource.slice(0,wrapperStart)+`import { invokeNative, readLocalKeys } from './internet-tools-api.js';
const promisifyNoFail=(area,name,outFn=r=>r)=>(...args)=>invokeNative(area,name,args,{outFn,noFail:true});
const promisify=(area,name)=>(...args)=>invokeNative(area,name,args);
`+webextSource.slice(wrapperEnd);fs.writeFileSync(webextFile,webextSource);
webextSource=webextSource.replace("get: promisify(chrome.storage.local, 'get'),","get: keys=>readLocalKeys(chrome.storage.local,keys),");fs.writeFileSync(webextFile,webextSource);
webextSource=require('./samsung-capabilities.cjs')(webextSource);fs.writeFileSync(webextFile,webextSource);
let source=fs.readFileSync(path.join(work,'js/vapi-background.js'),'utf8');
function once(old,value){if(!source.includes(old))throw Error('Pinned source does not match: '+old);source=source.replace(old,value);}
once('const cacheManagedStorage = async ( ) => {',`const readManagedBounded = () => new Promise(resolve => {
        let settled=false;
        const finish=value=>{if(settled)return;settled=true;clearTimeout(timer);resolve(value);};
        const timer=setTimeout(()=>finish(undefined),2000);
        try{webext.storage.managed.get(null).then(finish,()=>finish(undefined));}catch{finish(undefined);}
    });
    const cacheManagedStorage = async ( ) => {`);
once('store = await webext.storage.managed.get();','store = await readManagedBounded();');
once('vAPI.storage.set({ cachedManagedStorage: store || {} });','if (store instanceof Object) vAPI.storage.set({ cachedManagedStorage: store });');
once("vAPI.storage.get('cachedManagedStorage')","vAPI.storage.get(['cachedManagedStorage'])");
once('bin = await webext.storage.managed.get() || {};','bin = {}; // No synchronous managed-API dependency: refresh the cache below.');
once('return webext.storage.local.get(key, ...args).catch(reason => {', `const defaults=key!==null&&typeof key==='object'&&!Array.isArray(key)?key:null;
        const keys=defaults?Object.keys(defaults):typeof key==='string'?[key]:key==null?null:key;
        return webext.storage.local.get(keys, ...args).then(bin=>defaults&&bin instanceof Object?{...defaults,...bin}:bin).catch(reason => {`);
once("return webext.storage.local.remove(...args).catch(reason => {", "if(typeof args[0]==='string')args[0]=[args[0]];\n        return webext.storage.local.remove(...args).catch(reason => {");
// Keep the packaged icon on Samsung. Dynamic icon changes are cosmetic and
// the native endpoint never acknowledged them in the supplied phone reports.
source=source.replace('if ( webext.browserAction.setIcon ) {','if ( false ) {');
fs.writeFileSync(path.join(work,'js/vapi-background.js'),source);
let start=fs.readFileSync(path.join(work,'js/start.js'),'utf8');
if(!start.includes('(async ( ) => {'))throw Error('Pinned startup entry missing');

const phases=[['await µb.restoreAdminSettings();','restoreAdminSettings'],['await µb.loadHiddenSettings();','loadHiddenSettings'],['const adminExtra = await vAPI.adminStorage.get','readExtraAdminSettings'],['µb.supportStats.cacheBackend = await cacheStorage.select','selectCacheBackend'],['await vAPI.storage.get(createDefaultProps())','readStartupSettings']];
for(const [text,phase] of phases){if(!start.includes(text))throw Error('Pinned phase missing '+phase);start=start.replace(text,`self.__internetToolsDiagnostics?.phase('${phase}');\n    `+text);}

// Continue full engine initialization in the same background on Samsung.
const firstInstallRestart=`    if ( lastVersionInt === 0 && vAPI.webextFlavor.soup.has('chromium') ) {
        vAPI.app.restart();
        return;
    }`;
if(!start.includes(firstInstallRestart))throw Error('Pinned first-install restart missing');
start=start.replace(firstInstallRestart,`    self.__internetToolsFirstInstall = lastVersionInt === 0;
    // Continue loading the real engines and lists in this same background.`);
start=start.replace('µb.isReadyResolve();',"self.__internetToolsDiagnostics?.phase('initialized');\nµb.isReadyResolve();");

const tracked=[
 ['await µb.restoreAdminSettings()', 'restoreAdminSettings', 'µb.restoreAdminSettings()'],
 ['await µb.loadHiddenSettings()', 'loadHiddenSettings', 'µb.loadHiddenSettings()'],
 ['await onHiddenSettingsReady()', 'onHiddenSettingsReady', 'onHiddenSettingsReady()'],
 ["await vAPI.adminStorage.get('toAdd')", 'readExtraAdminSettings', "vAPI.adminStorage.get('toAdd')"],
 ['await vAPI.storage.get(createDefaultProps())', 'readStartupSettings', 'vAPI.storage.get(createDefaultProps())'],
 ['µb.loadSelectedFilterLists().then', 'loadSelectedFilterLists', 'µb.loadSelectedFilterLists()'],
 ['µb.loadUserSettings().then', 'loadUserSettings', 'µb.loadUserSettings()'],
 ['µb.loadPublicSuffixList().then', 'loadPublicSuffixList', 'µb.loadPublicSuffixList()'],
 ['cacheStorage.get({ compiledMagic: 0, selfieMagic: 0 }).then', 'readCacheMagic', 'cacheStorage.get({ compiledMagic: 0, selfieMagic: 0 })'],
 ['µb.loadLocalSettings(),', 'loadLocalSettings', 'µb.loadLocalSettings()'],
 ['await µb.selfieManager.load()', 'loadEngineCache', 'µb.selfieManager.load()'],
 ['await µb.loadFilterLists()', 'loadFilterLists', 'µb.loadFilterLists()'],
 ['await initializeTabs()', 'initializeTabs', 'initializeTabs()'],
];
for(const [match,name,call] of tracked){
 if(!start.includes(match))throw Error('Missing tracked startup call '+name);
 const replacement=`self.__internetToolsDiagnostics.track('${name}',()=>${call})`;
 start=start.replace(match,match.replace(call,replacement));
}
start=start.replace('await Promise.all([',"self.__internetToolsDiagnostics?.phase('loadStartupDependencies');\n    await Promise.all([");
start=start.replace('// Prime the filtering engines before first use.',"self.__internetToolsDiagnostics?.phase('loadFilteringEngines');\n// Prime the filtering engines before first use.");
start=start.replace('await initializeTabs()',"await initializeTabs()");
// Record explicit upstream reloads; never suppress them or claim they are crashes.
source=fs.readFileSync(path.join(work,'js/vapi-background.js'),'utf8');
source=source.replace('browser.runtime.reload();',"self.__internetToolsDiagnostics?.record('runtime_reload');\n        browser.runtime.reload();");
fs.writeFileSync(path.join(work,'js/vapi-background.js'),source);

start+='\n'+fs.readFileSync(path.join(root,'compat/bridge.js'),'utf8');
fs.writeFileSync(path.join(work,'js/start.js'),start);
(async()=>{
const acorn=require(process.env.ACORN_PACKAGE||'acorn');
fs.writeFileSync(path.join(work,'js/scriptlets-compat.js'),require('./bundle-resources.cjs')(work,acorn));
const redirectFile=path.join(work,'js/redirect-engine.js');let redirectSource=fs.readFileSync(redirectFile,'utf8');
if(!redirectSource.includes("import('/js/resources/scriptlets.js')"))throw Error('Pinned redirect module did not match');
redirectSource=redirectSource.replace("import('/js/resources/scriptlets.js')","Promise.resolve(globalThis.__internetToolsScriptletResources)");fs.writeFileSync(redirectFile,redirectSource);
const preserved=[];
await esbuild.build({plugins:[require('./preserve-function-strings.cjs')(work,acorn,preserved)],entryPoints:[path.join(work,'js/start.js')],outfile:path.join(work,'js/start-compat.js'),bundle:true,format:'iife',target:'chrome130',external:['/js/*'],logLevel:'warning'});
let html=fs.readFileSync(path.join(work,'background.html'),'utf8').replace('<script src="js/start.js" type="module"></script>','<script src="js/internet-tools-diagnostics.js"></script>\n<script src="js/scriptlets-compat.js"></script>\n<script src="js/start-compat.js"></script>');
if(!html.includes('start-compat.js'))throw Error('Pinned background HTML did not match');fs.writeFileSync(path.join(work,'background.html'),html);
fs.copyFileSync(path.join(root,'compat/diagnostics.js'),path.join(work,'js/internet-tools-diagnostics.js'));
const dashboardJs=path.join(work,'js/dashboard.js');
let dashboardSource=fs.readFileSync(dashboardJs,'utf8');
dashboardSource=dashboardSource.replace("if ( tabButton === null || dom.cl.has(tabButton, 'selected') ) { return; }", "if (tabButton === null && first && pane !== 'settings.html') return loadDashboardPanel('settings.html',true);\n    if ( tabButton === null || dom.cl.has(tabButton, 'selected') ) { return; }");
const dashboardEnd=dashboardSource.lastIndexOf('})();');
if(dashboardEnd<0)throw Error('Dashboard readiness marker missing');
dashboardSource=dashboardSource.slice(0,dashboardEnd)+"globalThis.__internetToolsDashboardReady=!!document.querySelector('.tabButton.selected');\n"+dashboardSource.slice(dashboardEnd);
fs.writeFileSync(dashboardJs,dashboardSource);
const commonFile=path.join(work,'js/dashboard-common.js');
fs.appendFileSync(commonFile, "\ndom.attr('a[data-internet-tools-local]', 'target', '_self');\n");
const popupFile=path.join(work,'js/popup-fenix.js');
let popupSource=fs.readFileSync(popupFile,'utf8');
popupSource=popupSource.replace("let url = dom.attr(ev.target, 'href');", "let url = dom.attr(this, 'href');\n    if(/^(dashboard|logger-ui)\\.html(?:[#?].*)?$/.test(url)){self.location.assign(url);return;}");
fs.writeFileSync(popupFile,popupSource);
const uiBundles=await require('./ui-bundle.cjs')(work,esbuild);
fs.writeFileSync(path.join(work,'internet-tools-ui.json'),JSON.stringify(uiBundles));
fs.writeFileSync(path.join(work,'internet-tools-build.json'),JSON.stringify({compatBuild:7,preservedInlineInjectorCount:preserved.length,preservedInlineInjectors:preserved.map(x=>x.file)}));

let dashboard=fs.readFileSync(path.join(work,'dashboard.html'),'utf8');
dashboard=dashboard.replace('<a class="wikilink', '<a class="tabButton" href="popup-fenix.html" data-internet-tools-local>Site controls</a><a class="wikilink');
dashboard=dashboard.replace('</head>','<link rel="stylesheet" href="css/internet-tools-dashboard.css">\n</head>');
fs.writeFileSync(path.join(work,'dashboard.html'),dashboard);
fs.writeFileSync(path.join(work,'css/internet-tools-dashboard.css'),`html,body{min-width:320px;min-height:480px}body.notReady{display:flex}body.notReady:before{content:"Starting uBlock Origin…";display:block;padding:24px;font:16px sans-serif}body.notReady #dashboard-nav,body.notReady #iframe{visibility:hidden}#iframe{min-height:0}`);
const manifest=JSON.parse(fs.readFileSync(path.join(work,'manifest.json')));
manifest.browser_action.default_popup='dashboard.html';
manifest.permissions=manifest.permissions.filter(permission=>permission!=='privacy');
fs.writeFileSync(path.join(work,'manifest.json'),JSON.stringify(manifest,null,2));
console.log(work);

})().catch(e=>{console.error(e);process.exitCode=1});
