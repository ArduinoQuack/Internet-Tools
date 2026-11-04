(() => {
    'use strict';
    const key = '__internetPrivateUboProbe_v1';
    if (typeof document === 'object' && document && document.querySelector('#main-frame-error, #error-page, .neterror')) {
        globalThis[key] = {state:'extension_page_blocked',probeVersion:'0.7.4',requestBlockingVerified:false};
        return 'probe_started';
    }
    if (location.protocol !== 'chrome-extension:' || location.hostname !== 'fkgkibajhfbepljeaefdnfnegdcjomkh') return 'wrong_origin';
    const out = globalThis[key] = {
        state: 'pending', probeVersion: '0.7.4',
        requestListenerRegistered: null, requestBlockingVerified: false
    };
    const apis = [globalThis.browser, globalThis.chrome].filter(Boolean);
    const runtimeApi = apis.find(a => a.runtime && typeof a.runtime.connect === 'function')
        || apis.find(a => a.runtime) || null;
    const messaging = globalThis.vAPI && globalThis.vAPI.messaging;
    out.capabilities = {
        vapiMessaging: !!messaging && typeof messaging.send === 'function',
        runtimeConnect: apis.some(a => a.runtime && typeof a.runtime.connect === 'function'),
        runtimeGetManifest: apis.some(a => a.runtime && typeof a.runtime.getManifest === 'function'),
        getBackgroundPage: apis.some(a => a.runtime && typeof a.runtime.getBackgroundPage === 'function')
    };
    out.execution = 'extension_main_world';
    if (typeof document === 'object' && document) {
        try {
            out.documentContext = {
                readyState: document.readyState,
                hasUboClientScript: !!document.querySelector('script[src$="vapi-client.js"]'),
                isErrorPage: !!document.querySelector('#main-frame-error, #error-page, .neterror')
            };
        } catch {}
    }
    let ownPort = null;
    let pendingId = 0;
    const pending = new Map();
    const finish = (state, error) => {
        if (out.state !== 'pending') return;
        out.state = state;
        if (error) out.error = String(error).slice(0, 700);
        clearTimeout(watchdog);
        // Never disconnect uBO's own dashboard port.
        if (ownPort) { try { ownPort.disconnect(); } catch {} ownPort = null; }
    };
    const watchdog = setTimeout(() => finish('probe_timeout', 'No complete background response within 40 seconds'), 40000);
    let send;
    const query = async what => {
        out.stage = what;
        let timer;
        try {
            return await Promise.race([
                Promise.resolve().then(() => send(what)),
                new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(what + ' response timed out')), 2000); })
            ]);
        } finally { clearTimeout(timer); }
    };
    if (out.capabilities.vapiMessaging) {
        out.transport = 'ubo_dashboard_messaging';
        send = what => messaging.send('dashboard', { what });
    } else if (out.capabilities.runtimeConnect) {
        out.transport = 'ubo_runtime_port';
        // Same {channel,msgId,msg}/{msgId,msg} protocol as upstream vapi-client.js.
        // Only three read-only dashboard requests are used.
        send = what => {
            if (!ownPort) {
                ownPort = runtimeApi.runtime.connect({ name: 'internet-private-probe-' + Math.random().toString(36).slice(2) });
                if (!ownPort) throw new Error('runtime.connect returned no port');
                ownPort.onMessage.addListener(reply => {
                    if (!reply || !pending.has(reply.msgId)) return;
                    const callback = pending.get(reply.msgId); pending.delete(reply.msgId); callback.resolve(reply.msg);
                });
                ownPort.onDisconnect.addListener(() => {
                    for (const callback of pending.values()) callback.reject(new Error('uBO background port disconnected'));
                    pending.clear(); ownPort = null;
                });
            }
            const msgId = ++pendingId;
            return new Promise((resolve, reject) => {
                pending.set(msgId, { resolve, reject });
                ownPort.postMessage({ channel: 'dashboard', msgId, msg: { what } });
            });
        };
    } else {
        finish(out.documentContext && out.documentContext.isErrorPage ? 'extension_page_error' : 'unsupported_extension_messaging', 'No uBO messaging API is exposed in the native extension main world');
        return 'probe_started';
    }
    // Extra observations are optional. Absence is unknown, never a failed core check.
    out.blockingPermission = null;
    const manifestApi = apis.find(a => a.runtime && typeof a.runtime.getManifest === 'function');
    if (manifestApi) {
        try {
            const manifest = manifestApi.runtime.getManifest();
            if (manifest && Array.isArray(manifest.permissions)) out.blockingPermission = manifest.permissions.includes('webRequestBlocking');
        } catch {}
    }
    const bgApi = apis.find(a => a.runtime && typeof a.runtime.getBackgroundPage === 'function');
    if (bgApi) {
        try {
            bgApi.runtime.getBackgroundPage(bg => {
                if (out.state !== 'pending' || !bg) return;
                try {
                    const api = bg.browser || bg.chrome;
                    if (api && api.webRequest && api.webRequest.onBeforeRequest
                        && typeof api.webRequest.onBeforeRequest.hasListeners === 'function') {
                        out.requestListenerRegistered = api.webRequest.onBeforeRequest.hasListeners() === true;
                    }
                } catch {}
            });
        } catch {}
    }
    // Read only startup metadata. Never enumerate tabs, requests, lists or settings.
    const startupSnapshot = () => new Promise(resolve => {
        if (!bgApi) return resolve({ backgroundAccessible: false });
        const snapshot = { backgroundAccessible: false };
        let done = false;
        const finishSnapshot = () => { if (!done) { done = true; clearTimeout(timer); resolve(snapshot); } };
        const timer = setTimeout(() => { if (!snapshot.storageRead) snapshot.storageRead = 'timeout'; if (snapshot.managedStorageRead === 'pending') snapshot.managedStorageRead = 'timeout'; finishSnapshot(); }, 1500);
        try {
            bgApi.runtime.getBackgroundPage(bg => {
                if (done || !bg) return finishSnapshot();
                try {
                    snapshot.backgroundAccessible = true;
                    const core = bg.µBlock;
                    snapshot.corePresent = !!core;
                    if (core) {
                        snapshot.readyToFilter = core.readyToFilter === true;
                        const stats = core.supportStats || {};
                        snapshot.supportStats = {};
                        for (const key of ['allReadyAfter', 'maxAssetCacheWait', 'cacheBackend', 'unsuspendAfter']) {
                            if (typeof stats[key] === 'string' || typeof stats[key] === 'number') snapshot.supportStats[key] = stats[key];
                        }
                        if (Array.isArray(core.selectedFilterLists)) snapshot.selectedListCount = core.selectedFilterLists.length;
                        if (typeof core.apiErrorCount === 'number') snapshot.apiErrorCount = core.apiErrorCount;
                    }
                    if (bg.vAPI && typeof bg.vAPI.T0 === 'number') snapshot.startupAgeMs = Math.max(0, Date.now() - bg.vAPI.T0);
                    const api = bg.chrome || bg.browser;
                    if (!api || !api.storage || !api.storage.local || typeof api.storage.local.get !== 'function') {
                        snapshot.storageRead = 'unavailable'; return finishSnapshot();
                    }
                    // These are only startup metadata/cache presence and API completion checks.
                    // Managed storage is never modified, and its contents are not copied to the report.
                    const hasManaged = !!api.storage.managed && typeof api.storage.managed.get === 'function';
                    snapshot.managedStorageRead = hasManaged ? 'pending' : 'unavailable';
                    let localDone = false, managedDone = !hasManaged;
                    const complete = () => { if (localDone && managedDone) finishSnapshot(); };
                    api.storage.local.get(['version', 'cachedManagedStorage'], data => {
                        if (done) return;
                        snapshot.storageRead = api.runtime && api.runtime.lastError ? 'error' : 'responded';
                        if (data && typeof data.version === 'string') snapshot.storedVersion = data.version;
                        if (data && typeof data === 'object') snapshot.managedCachePresent = Object.hasOwn(data, 'cachedManagedStorage');
                        localDone = true; complete();
                    });
                    if (hasManaged) {
                        try {
                            api.storage.managed.get('adminSettings', () => {
                                if (done) return;
                                snapshot.managedStorageRead = api.runtime && api.runtime.lastError ? 'error' : 'responded';
                                managedDone = true; complete();
                            });
                        } catch { snapshot.managedStorageRead = 'threw'; managedDone = true; complete(); }
                    }
                } catch { snapshot.inspectionError = true; finishSnapshot(); }
            });
        } catch { snapshot.inspectionError = true; finishSnapshot(); }
    });
    (async () => {
        try {
            const app = await query('getAppData');
            if (!app || app.name !== 'uBlock Origin' || typeof app.version !== 'string') {
                finish('background_no_reply', 'The dashboard did not return valid uBO application data'); return;
            }
            out.version = app.version;
            let ready = false;
            out.readinessChecks = 0;
            for (let attempt = 0; attempt < 17; attempt++) {
                ready = await query('readyToFilter');
                out.readinessChecks++;
                if (ready === true) break;
                if (attempt < 16) await new Promise(resolve => setTimeout(resolve, 750));
            }
            out.readyToFilter = ready === true;
            if (ready !== true) {
                out.startup = await startupSnapshot();
                finish('runtime_incomplete', 'uBO background is responding but filtering startup has not completed'); return;
            }
            const lists = await query('getLists');
            if (!lists || typeof lists.netFilterCount !== 'number' || typeof lists.cosmeticFilterCount !== 'number'
                || !Number.isFinite(lists.netFilterCount) || !Number.isFinite(lists.cosmeticFilterCount)) {
                finish('runtime_incomplete', 'uBO did not return valid loaded-filter counts'); return;
            }
            out.networkFilterCount = lists.netFilterCount;
            out.cosmeticFilterCount = lists.cosmeticFilterCount;
            if (typeof lists.autoUpdate === 'boolean') out.filterAutoUpdate = lists.autoUpdate;
            out.verificationLevel = 'background_messaging_and_loaded_rules';
            out.note = 'Background readiness and loaded rules only. Actual request blocking, scriptlet execution and anti-adblock behavior still need live tests.';
            finish(out.networkFilterCount > 0 && out.blockingPermission !== false && out.requestListenerRegistered !== false
                ? 'runtime_ready' : 'runtime_incomplete');
        } catch (error) { finish('probe_failed', error); }
    })();
    return 'probe_started';
})()
