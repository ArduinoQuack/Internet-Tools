"""Validate module hook names against definitions in the actual supplied APK.

No third-party parser required. Reads DEX class_data, not JADX-renamed aliases.
Usage: python3 tests/verify_targets.py '/path/to/Samsung Browser.apk'
"""
from pathlib import Path
import hashlib
import json
import re
import struct
import sys
import zipfile


def definitions(data):
    def u32(off): return struct.unpack_from('<I', data, off)[0]
    def uleb(off):
        v = shift = 0
        while True:
            b = data[off]; off += 1; v |= (b & 127) << shift; shift += 7
            if b < 128: return v, off
    ns, so = u32(56), u32(60)
    ss = []
    for i in range(ns):
        _, off = uleb(u32(so + i * 4))
        ss.append(data[off:data.index(0, off)].decode('utf-8', 'replace'))
    nt, to = u32(64), u32(68)
    tt = [ss[u32(to + i * 4)] for i in range(nt)]
    nm, mo = u32(88), u32(92)
    mm = [ss[u32(mo + i * 8 + 4)] for i in range(nm)]
    nc, co = u32(96), u32(100)
    result = {}
    for i in range(nc):
        off = co + i * 32
        name = tt[u32(off)]
        p = u32(off + 24)
        names = set()
        if p:
            counts = []
            for _ in range(4):
                n, p = uleb(p); counts.append(n)
            for _ in range(counts[0] + counts[1]):
                _, p = uleb(p); _, p = uleb(p)
            for count in counts[2:]:
                idx = 0
                for _ in range(count):
                    delta, p = uleb(p); idx += delta
                    _, p = uleb(p); _, p = uleb(p)
                    names.add(mm[idx])
        result[name[1:-1].replace('/', '.')] = names
    return result


def main():
    apk = Path(sys.argv[1])
    src = '\n'.join(p.read_text() for p in (Path(__file__).resolve().parents[1] / 'src/dev/dov/internettools').glob('*.java'))
    targets = {}
    for name in ['stop', 'constant', 'hook']:
        # Only install calls with a literal class name, excluding helper definitions.
        pattern = name + r'\(cl,\s*(P\s*\+\s*)?"([^"]+)",\s*(.*?)' + (r'\);' if name == 'stop' else r'(?:new XC_MethodHook\(|(?:true|false)\);)')
        for m in re.finditer(pattern, src, re.S):
            cl = ('com.sec.android.app.sbrowser.' if m[1] else '') + m[2]
            methods = re.findall(r'"([^"]+)"', m[3])
            targets.setdefault(cl, set()).update(methods)
    assert targets, 'No hook calls parsed'
    runtime_targets = {
        'org.chromium.base.CommandLine': {'getInstance', 'isInitialized', 'appendSwitch', 'appendSwitchWithValue', 'getSwitchValue'},
        'com.sec.terrace.TerraceHelper': {'getInstance', 'isInitialized', 'getCurrentTerraceActivity'},
        'com.sec.terrace.browser.extensions.TerraceExtensionsLoader': {'getInstance', 'initialized', 'launchInstallDefaultCrx', 'changeExtensionStatus', 'launchUninstallCrx', 'reloadExtension', 'reviveTerminatedExtIfExist'},
        'com.sec.terrace.browser.extensions.TerraceExtensionUtil': {'getInstance', 'isInstalledInNative', 'isEnabledInNative', 'getInstalledExtVersion', 'canBeSecretModeEnabled', 'getSecretModeState', 'updateSecretModeState', 'setInstallSource', 'getExtensionOptionPage'},
        'com.sec.android.app.sbrowser.main_view.MainViewLayout': {'getCurrentTab', 'setSecretModeEnabled'},
        'com.sec.android.app.sbrowser.main_view.MainViewImpl': {'loadUrlWithNewTab'},
        'com.sec.android.app.sbrowser.sbrowser_tab.SBrowserTabBase': {'getTerrace', 'getUrl'},
        'com.sec.terrace.Terrace': {'evaluateJavaScriptForTests', 'getUrl', 'isLoading', 'loadUrl', 'getWebContents'},
        'com.sec.terrace.browser.extensions.TerraceExtensionsManager': {'getInstance', 'initialize', 'isInitialized', 'executeAction'},
        'com.sec.terrace.browser.extensions.TerraceExtensionsManagerJni': {'get', 'initExtensions'},
        'com.sec.terrace.browser.extensions.TerraceExtensionsManager$OnResultInitCallback': {'onSuccess', 'onFailure'},
        'org.chromium.content_public.browser.WebContents': {'getLastCommittedUrl'},
        'org.chromium.url.GURL': {'getSpec'},
        'com.sec.terrace.browser.TerraceLoadUrlParams': {'<init>', 'setHasUserGesture'},
        'com.sec.terrace.browser.extensions.TinAndroidExtensionView': {'dismissPopup'},
        'com.sec.terrace.TerraceApplicationStatus': {'getStateForActivity'},
        'com.sec.terrace.TerraceJavaScriptCallback': {'handleJavaScriptResult'},
    }
    classes = {}
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex', name): classes.update(definitions(z.read(name)))
    missing = {c: sorted(names - classes.get(c, set())) for c, names in targets.items()
               if names - classes.get(c, set())}
    missing_runtime = {c: sorted(names - classes.get(c, set())) for c, names in runtime_targets.items()
                       if names - classes.get(c, set())}
    report = dict(apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                  hook_classes=len(targets), hook_method_names=sum(map(len, targets.values())),
                  targets={c: sorted(v) for c, v in targets.items()}, missing=missing,
                  reflection_targets={c: sorted(v) for c, v in runtime_targets.items()},
                  missing_reflection_targets=missing_runtime,
                  validation='DEX method existence only; no Android runtime or network-traffic test')
    out = Path(__file__).resolve().parents[1] / 'target-validation.json'
    out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k,v in report.items() if k not in ('targets', 'reflection_targets')}, indent=2))
    assert not missing, 'Some hook targets do not exist in this APK'
    assert not missing_runtime, 'Some runtime reflection targets do not exist in this APK'


if __name__ == '__main__': main()
