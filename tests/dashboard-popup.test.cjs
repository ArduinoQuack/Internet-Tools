const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),ext=path.resolve(process.argv[2]||path.join(root,'validation/extension'));
 const {Window}=await import(path.join(root,'compat/node_modules/happy-dom/lib/index.js'));
 const manifest=JSON.parse(fs.readFileSync(path.join(ext,'manifest.json')));
 assert.equal(manifest.browser_action.default_popup,'dashboard.html');
 const pages=JSON.parse(fs.readFileSync(path.join(ext,'internet-tools-ui.json')));assert.ok(pages.length>15);
 const acorn=require(path.join(root,'compat/node_modules/acorn'));
 for(const page of pages){
  const html=fs.readFileSync(path.join(ext,page.page),'utf8');assert.ok(!html.includes('type="module"'),page.page);
  assert.ok(html.includes(page.bundle));acorn.parse(fs.readFileSync(path.join(ext,page.bundle),'utf8'),{ecmaVersion:'latest',sourceType:'script'});
 }
 const origin='chrome-extension://odkabbfgkiaeajgdfcfhnbkmjjkolehf/';
 const w=new Window({url:origin+'dashboard.html',settings:{disableCSSFileLoading:true,disableJavaScriptFileLoading:true,disableIframePageLoading:true,enableJavaScriptEvaluation:true}});
 w.document.write(fs.readFileSync(path.join(ext,'dashboard.html'),'utf8'));
 const paneRequests=[];Object.defineProperty(w.document.querySelector('#iframe'),'contentWindow',{value:{location:{replace:p=>paneRequests.push(p)},wikilink:''},configurable:true});
 let ready=false,readyCalls=0;const requests=[];
 const messages=JSON.parse(fs.readFileSync(path.join(ext,'_locales/en/messages.json')));
 w.chrome=w.browser={i18n:{getMessage:k=>messages[k]?.message||k},runtime:{getURL:p=>origin+p,getManifest:()=>manifest}};
 w.vAPI={webextFlavor:{soup:new Set(['mobile','chromium'])},defer:{once:ms=>new Promise(r=>setTimeout(r,Math.min(10,ms)))},localStorage:{getItemAsync:async()=>null,setItem:()=>{}},messaging:{send:async(channel,msg)=>{
  requests.push(msg.what);if(msg.what==='readyToFilter'){readyCalls++;return ready;}
  if(msg.what==='dashboardConfig')return {};
  if(msg.what==='uiStyles')return {uiTheme:'dark',uiStyles:'unset'};
  return {};
 }}};
 const stubs={...w.vAPI};w.eval(fs.readFileSync(path.join(ext,'js/vapi-common.js'),'utf8'));w.vAPI.messaging=stubs.messaging;w.vAPI.localStorage=stubs.localStorage;
 w.eval(fs.readFileSync(path.join(ext,'js/ui-dashboard.js'),'utf8'));
 await new Promise(r=>setTimeout(r,40));
 assert.equal(w.__internetToolsDashboardReady,undefined);assert.ok(w.document.body.classList.contains('notReady'));assert.ok(readyCalls>=1);
 const css=fs.readFileSync(path.join(ext,'css/internet-tools-dashboard.css'),'utf8');assert.ok(css.includes('body.notReady{display:flex}'));assert.ok(css.includes('min-height:480px'));
 ready=true;await new Promise(r=>setTimeout(r,350));
 assert.equal(w.__internetToolsDashboardReady,true);assert.ok(!w.document.body.classList.contains('notReady'));
 assert.ok(paneRequests.includes('settings.html'));
 assert.ok(w.document.querySelector('[data-pane="settings.html"]').classList.contains('selected'));
 assert.equal(w.document.querySelector('a[data-internet-tools-local]').target,'_self');
 const button=w.document.querySelector('[data-pane="3p-filters.html"]');button.click();await new Promise(r=>setTimeout(r,30));
 assert.ok(paneRequests.includes('3p-filters.html'));
 await w.happyDOM.close();
 console.log('PASS: '+pages.length+' real UI pages parse as classic scripts; dashboard waits for real readiness, opens settings, requests its filter tab and keeps site controls in its native view');
})().catch(e=>{console.error(e);process.exit(1)});
