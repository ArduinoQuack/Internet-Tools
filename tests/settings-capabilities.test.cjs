const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),ext=path.resolve(process.argv[2]||path.join(root,'validation/extension'));
 const {Window}=await import(path.join(root,'compat/node_modules/happy-dom/lib/index.js'));
 const origin='chrome-extension://odkabbfgkiaeajgdfcfhnbkmjjkolehf/';
 const manifest=JSON.parse(fs.readFileSync(path.join(ext,'manifest.json')));
 assert.ok(!manifest.permissions.includes('privacy'));for(const permission of ['storage','tabs','webRequest','webRequestBlocking','<all_urls>'])assert.ok(manifest.permissions.includes(permission));
 const messages=JSON.parse(fs.readFileSync(path.join(ext,'_locales/en/messages.json')));
 for(const supported of [true,false]){
  const w=new Window({url:origin+'settings.html',settings:{disableCSSFileLoading:true,disableJavaScriptFileLoading:true,disableIframePageLoading:true,enableJavaScriptEvaluation:true,suppressInsecureJavaScriptEnvironmentWarning:true}});
  w.document.write(fs.readFileSync(path.join(ext,'settings.html'),'utf8'));
  const values={};for(const e of w.document.querySelectorAll('[data-setting-type="bool"]'))values[e.dataset.settingName]=false;
  const send=async(_channel,msg)=>{switch(msg.what){case 'uiStyles':return {uiTheme:'dark',uiStyles:'unset'};case 'userSettings':return values;case 'getLocalData':return {privacySettingsSupported:supported,storageUsed:1024};default:return {};}};
  w.chrome=w.browser={i18n:{getMessage:k=>messages[k]?.message||k},runtime:{getURL:p=>origin+p,getManifest:()=>manifest}};
  w.vAPI={};w.eval(fs.readFileSync(path.join(ext,'js/vapi-common.js'),'utf8'));w.vAPI.messaging={send};
  w.eval(fs.readFileSync(path.join(ext,'js/ui-settings.js'),'utf8'));
  await new Promise(r=>setTimeout(r,30));
  for(const name of ['prefetchingDisabled','hyperlinkAuditingDisabled','webrtcIPAddressHidden'])assert.equal(w.document.querySelector('[data-setting-name="'+name+'"]').disabled,!supported,name);
  assert.equal(w.document.querySelector('[data-setting-name="noCosmeticFiltering"]').disabled,false);
  assert.equal(w.document.querySelector('[data-setting-name="advancedUserEnabled"]').disabled,false);
  await w.happyDOM.close();
 }
 console.log('PASS: real bundled settings UI disables only unsupported browser-preference controls; filtering controls remain usable and manifest retains blocking permissions');
})().catch(e=>{console.error(e);process.exit(1)});
