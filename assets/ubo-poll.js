(() => {
    const version = '0.7.4';
    const invalid = state => JSON.stringify({state, probeVersion:version, requestBlockingVerified:false});
    if (typeof document === 'object' && document && document.querySelector('#main-frame-error, #error-page, .neterror')) return invalid('extension_page_blocked');
    if (location.protocol !== 'chrome-extension:' || location.hostname !== 'fkgkibajhfbepljeaefdnfnegdcjomkh') return invalid('probe_origin_changed');
    const out = globalThis.__internetPrivateUboProbe_v1;
    if (!out || typeof out !== 'object' || out.probeVersion !== version) return invalid('probe_context_lost');
    return JSON.stringify(out);
})()
