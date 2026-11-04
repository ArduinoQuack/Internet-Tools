package dev.dov.internettools;

import android.content.Context;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.zip.ZipFile;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses Samsung's existing installer with a valid, independently signed compatibility CRX. */
final class UboInstaller {
    static final String ID = ExtensionConfig.ID;
    static final String SHA256 = ExtensionConfig.SHA256;
    private static final String LOADER = "com.sec.terrace.browser.extensions.TerraceExtensionsLoader";
    private static final String UTIL = "com.sec.terrace.browser.extensions.TerraceExtensionUtil";
    private static final Uri CONTROL = Uri.parse("content://dev.dov.internettools.status");
    private final ClassLoader cl;
    private final boolean mainProcess;
    private final String modulePath;
    private PendingIntent reply;
    private PendingIntent returnToApp;
    private PendingIntent inspectionReturn;
    private String inspectionReturnId;
    private boolean returnReceiverRegistered;
    private String pendingCommand;
    private String requestId;
    private java.lang.ref.WeakReference<Activity> controlActivity = new java.lang.ref.WeakReference<>(null);
    private long controlStarted;
    private final java.util.LinkedHashSet<String> seenRequests = new java.util.LinkedHashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Context app;
    private Object mainView;
    private boolean checkingCommand;
    private volatile boolean installing;
    private volatile boolean copying;
    private int probeGeneration;
    private int serviceGeneration;
    private boolean startingService;
    private boolean verifying;
    private boolean waitingForPopup;
    private boolean operationActive;
    private String activeCommand;
    private boolean diagnosticMode;
    private boolean checkToastShown;
    private Object popupOwner;
    private Object popupTerrace;
    private String nativeState = "";
    private boolean runtimeReloadAttempted;
    private JSONObject beforeReload;
    private JSONObject initialManagedFailure, managedRepairState;
    private boolean managedRepairAttempted, firstInstallReloadAttempted;
    private volatile String consoleReport = "Console capture has not run";
    private static final String MANAGER = "com.sec.terrace.browser.extensions.TerraceExtensionsManager";

    UboInstaller(Hook owner, ClassLoader loader, boolean main, String apkPath) {
        cl = loader; mainProcess = main; modulePath = apkPath;
        owner.hook(cl, "org.chromium.base.CommandLine", "init", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { configureSwitches(); }
        });
        owner.hook(cl, "org.chromium.base.CommandLine", "switchToNativeImpl", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { configureSwitches(); }
        });
        if (!mainProcess) return;
        XC_MethodHook accept = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = null;
                Intent intent = null;
                for (Object arg : p.args) {
                    if (arg instanceof Activity) activity = (Activity) arg;
                    if (arg instanceof Intent) intent = (Intent) arg;
                }
                if (activity == null || !browserActivity(activity)) return;
                acceptCommand(activity, intent == null ? activity.getIntent() : intent);
            }
        };
        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnCreate", accept);
        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnNewIntent", accept);
        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof Activity)) return;
                Activity activity = (Activity) p.args[0];
                if (!browserActivity(activity)) return;
                captureView(activity);
                if (pendingCommand != null && mainView != null) {
                    try { XposedHelpers.callMethod(mainView, "setSecretModeEnabled", false); }
                    catch (Throwable ignored) { }
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof Activity)) return;
                Activity activity = (Activity) p.args[0];
                if (!browserActivity(activity)) return;
                captureView(activity);
                if (pendingCommand != null) ui.postDelayed(() -> checkCommand(0), 300);
            }
        });
        for(String method:new String[]{"reloadExtension","reviveTerminatedExtIfExist","changeExtensionStatus"}) {
            owner.hook(cl,LOADER,method,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(ours(p.args))BrowserTrace.add("native_"+p.method.getName());
                }
            });
        }
        owner.hook(cl,"com.sec.terrace.browser.extensions.TinAndroidExtensionView$6","onReportCrash",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                try{Object parent=XposedHelpers.getObjectField(p.thisObject,"this$0");
                    Object terrace=XposedHelpers.getObjectField(parent,"mExtensionsTerrace");
                    if(parent==popupOwner||(terrace!=null&&ownExtension(terrace)))BrowserTrace.add("own_popup_renderer_crash_"+(p.args.length>1?String.valueOf(p.args[1]):"unknown"));
                }catch(Throwable ignored){}
            }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TinAndroidExtensionView", "launchExtensionsRenderView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { trackPopup(p.thisObject, 0); }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TinAndroidExtensionView", "launchExtensionView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                trackPopup(p.thisObject, 0);
            }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TinAndroidExtensionView", "dismissExtensionsRenderView", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject != popupOwner) return;
                BrowserTrace.add("own_popup_dismissed");
                popupOwner = null; popupTerrace = null;
            }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TerraceExtensionsLoader", "crxInstalled", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!ours(p.args)) return;
                // This direct installation has no pending Samsung SIX wrapper package.
                // Avoid dispatching it into SIX's pending-package bookkeeping.
                boolean ownInstall=installing&&operationActive&&"install".equals(activeCommand);
                final String receiptRequest=requestId;
                p.setResult(null); installing = false;
                ui.post(() -> {
                    if(ownInstall&&operationActive&&receiptRequest.equals(requestId)){
                        try{if(isInstalled())RuntimeBuildStamp.recordInstallReceipt(installReceipt(),SHA256);}catch(java.io.IOException ignored){}
                    }
                    afterInstall();
                });
            }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TerraceExtensionsLoader", "crxInstallFailed", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!ours(p.args)) return;
                p.setResult(null); installing = false;
                if (!operationActive || !"install".equals(activeCommand)) return;
                String code = p.args.length > 3 ? String.valueOf(p.args[3]) : "unknown";
                String why = p.args.length > 4 ? String.valueOf(p.args[4]) : "No native error text";
                event("install_rejected", "Samsung's native installer rejected the compatibility CRX. Code=" + code + "\n" + bounded(why));
            }
        });
        owner.hook(cl, "com.sec.terrace.browser.extensions.TerraceExtensionsLoader", "crxUninstalled", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!ours(p.args)) return;
                p.setResult(null);
                if (!operationActive || !"remove".equals(activeCommand)) return;
                ui.post(() -> {
                    if (!operationActive || !"remove".equals(activeCommand)) return;
                    try {
                        boolean exists = isInstalled(); updateNativeState();
                        if(!exists){runtimeMarker().delete();installReceipt().delete();}
                        event(exists ? "remove_failed" : "removed", exists ? "uBO remains installed." : "uBO was removed from Samsung's extension registry.");
                    } catch (Throwable e) { event("remove_status_unavailable", shortError(e)); }
                });
            }
        });
    }

    void attach(Context context) { if (mainProcess) app = context; }
    private File runtimeMarker(){return new File(app.getFilesDir(),"internet-tools-"+ID+".enabled");}
    private File installReceipt(){return new File(app.getFilesDir(),"internet-tools-"+ID+".installed");}
    private boolean currentRuntimeInstalled(){return RuntimeBuildStamp.matches(runtimeMarker(),SHA256)||RuntimeBuildStamp.matches(installReceipt(),SHA256);}
    private void confirmRuntimeBuild(Intent result){
        try{RuntimeBuildStamp.confirm(runtimeMarker(),ExtensionConfig.VERSION,result.getStringExtra("extensionVersion"),SHA256);}catch(java.io.IOException ignored){}
    }
    void onBrowserReady(){
        if(!mainProcess)return;
        ui.post(()->{if(app!=null&&runtimeMarker().isFile()&&!operationActive&&!startingService&&pendingCommand==null)ensureService(()->{});});
    }
    boolean isControlActivity(Activity activity) {
        return controlActivity.get() == activity && System.currentTimeMillis() - controlStarted < 15000;
    }

    private void configureSwitches() {
        try {
            Class<?> type = XposedHelpers.findClass("org.chromium.base.CommandLine", cl);
            if (!Boolean.TRUE.equals(XposedHelpers.callStaticMethod(type, "isInitialized"))) return;
            Object command = XposedHelpers.callStaticMethod(type, "getInstance");
            XposedHelpers.callMethod(command, "appendSwitch", "allow-legacy-extension-manifests");
            mergeSwitch(command, "disable-features", "ExtensionsManifestV3Only");
            mergeSwitch(command, "enable-features", "EnableExtensions");
        } catch (Throwable e) { XposedBridge.log("InternetTools: uBO startup switches unavailable"); }
    }

    private static void mergeSwitch(Object command, String key, String value) {
        String prior = (String) XposedHelpers.callMethod(command, "getSwitchValue", key);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (prior != null && !prior.isEmpty()) values.addAll(Arrays.asList(prior.split(",")));
        values.add(value);
        StringBuilder joined = new StringBuilder();
        for (String s : values) { if (joined.length() > 0) joined.append(','); joined.append(s); }
        XposedHelpers.callMethod(command, "appendSwitchWithValue", key, joined.toString());
    }

    private static boolean browserActivity(Activity activity) {
        return activity.getClass().getName().matches("com\\.sec\\.android\\.app\\.sbrowser\\.SBrowserMainActivity[0-9]*");
    }

    private void captureView(Activity activity) {
        app = activity.getApplicationContext();
        try { mainView = XposedHelpers.getObjectField(activity, "mMainView"); }
        catch (Throwable e) { mainView = null; }
    }

    private void acceptCommand(Activity activity, Intent intent) {
        if (intent == null || !BridgeContract.ACTION.equals(intent.getAction())) return;
        try {
            if (!BridgeContract.validEnvelope(intent)) return;
            PendingIntent callback = intent.getParcelableExtra(BridgeContract.REPLY);
            PendingIntent returnCapability=intent.getParcelableExtra(BridgeContract.RETURN);
            returnToApp=returnCapability!=null&&"dev.dov.internettools".equals(returnCapability.getCreatorPackage())?returnCapability:null;
            String command = intent.getStringExtra(BridgeContract.COMMAND);
            diagnosticMode=intent.getBooleanExtra(BridgeContract.DIAGNOSTICS,false);
            BrowserTrace.verbose=diagnosticMode;
            String id = intent.getStringExtra(BridgeContract.REQUEST);
            // The OS authenticates the PendingIntent creator. No provider visibility or shared preference polling is needed.
            intent.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            intent.removeExtra(BridgeContract.REPLY);
            intent.removeExtra(BridgeContract.RETURN);
            intent.removeExtra(BridgeContract.DIAGNOSTICS);
            intent.removeExtra(BridgeContract.COMMAND);
            intent.removeExtra(BridgeContract.REQUEST);
            if (!seenRequests.add(id)) return;
            while (seenRequests.size() > 32) seenRequests.remove(seenRequests.iterator().next());
            probeGeneration++; verifying = false; waitingForPopup = false;
            runtimeReloadAttempted = false; beforeReload = null;
            managedRepairAttempted = false; firstInstallReloadAttempted = false; initialManagedFailure = null; managedRepairState = null;
            reply = callback; requestId = id; pendingCommand = command;
            operationActive = true; activeCommand = command; checkToastShown = false;
            ui.postDelayed(() -> {
                if (!operationActive || !id.equals(requestId)) return;
                event("operation_timeout", "The " + command + " action reached its total time limit. All pending callbacks for this request were cancelled.");
            }, OperationState.budget(command));
            if(diagnosticMode)collectUboConsole(id);
            controlActivity = new java.lang.ref.WeakReference<>(activity); controlStarted = System.currentTimeMillis();
            captureView(activity);
            event("received", "Browser received " + command);
            ui.postDelayed(() -> checkCommand(0), 300);
        } catch (Throwable e) { event("command_failed", shortError(e)); }
    }

    private void checkCommand(int attempt) {
        if (!operationActive || pendingCommand == null || checkingCommand) return;
        if (app == null || mainView == null) {
            if (attempt < 20) ui.postDelayed(() -> checkCommand(attempt + 1), 400);
            else { pendingCommand = null; event("view_unavailable", "Browser received the action but its main view was unavailable."); }
            return;
        }
        try {
            Object helper = singleton("com.sec.terrace.TerraceHelper");
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isInitialized"))) {
                if (attempt < 20) ui.postDelayed(() -> checkCommand(attempt + 1), 400);
                else { pendingCommand = null; event("engine_unavailable", "Browser engine did not become ready."); }
                return;
            }
            checkingCommand = true;
            String action = pendingCommand; pendingCommand = null;
            event("running", "Running " + action);
            if ("remove".equals(action)) { updateNativeState(); remove(); return; }
            if ("install".equals(action)||"runtime".equals(action)||"runtime_open".equals(action)) XposedHelpers.callMethod(mainView,"setSecretModeEnabled",false);
            final String serviceRequest=requestId;
            ensureService(() -> {
                if(!operationActive||!serviceRequest.equals(requestId))return;
                if ("runtime".equals(action)||"runtime_open".equals(action)) {
                    XposedHelpers.callMethod(mainView,"setSecretModeEnabled",false);
                    updateNativeState();
                    if (!isInstalled()) {event("not_installed","uBO is not installed.");return;}
                    activateRuntime();
                }
                else if ("install".equals(action)) install();
                else if ("open".equals(action)) openDashboard(false);
                else if ("verify".equals(action)||"undo_fix".equals(action)) openAndVerify();
                else if ("remove".equals(action)) remove();
            });
        } catch (Throwable e) {
            pendingCommand = null;
            event("command_failed", shortError(e));
        } finally { checkingCommand = false; }
    }

    private void updateNativeState() {
        JSONObject state = new JSONObject();
        try {
            Object util = singleton(UTIL);
            boolean installed = Boolean.TRUE.equals(XposedHelpers.callMethod(util, "isInstalledInNative", ID));
            state.put("lifecycle",BrowserTrace.snapshot());
            state.put("samsungPrivacy",BrowserSettings.privacy).put("normalOutsideLinks",BrowserSettings.normalLinks);
            state.put("registered", installed);
            state.put("enabled", installed && Boolean.TRUE.equals(XposedHelpers.callMethod(util, "isEnabledInNative", ID)));
            Object manager = singleton(MANAGER);
            state.put("extensionServiceReady", Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isInitialized")));
            if (installed) {
                state.put("nativeVersionLabel", String.valueOf(XposedHelpers.callMethod(util, "getInstalledExtVersion", ID)));
                state.put("installSource", String.valueOf(XposedHelpers.callMethod(util, "getInstallSource", ID)));
                Object permissions = XposedHelpers.callMethod(util, "getExtensionPermissions", ID);
                if (permissions instanceof java.util.Collection) state.put("nativePermissions", new org.json.JSONArray((java.util.Collection<?>) permissions));
            }
        } catch (Throwable e) {
            try { state.put("inspectionError", shortError(e)); } catch (Exception ignored) { }
        }
        nativeState = state.toString();
    }

    private void ensureService(Runnable action) {
        if(startingService){ui.postDelayed(()->ensureService(action),200);return;}
        if (startingService) { event("service_busy", "The extension service is starting."); return; }
        try {
            Object manager = singleton(MANAGER);
            XposedHelpers.callMethod(manager, "initialize");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isInitialized"))) {
                updateNativeState(); action.run(); return;
            }
            long pointer = XposedHelpers.getLongField(manager, "mNativeTinExtensionsManager");
            if (pointer == 0) throw new IllegalStateException("Native extension manager is unavailable");
            startingService = true;
            final int ticket = ++serviceGeneration;
            event("starting_extension_service", "Starting Samsung's native extension service.");
            Class<?> callback = XposedHelpers.findClass(MANAGER + "$OnResultInitCallback", cl);
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, (obj, method, args) -> {
                String name = method.getName();
                if ("onSuccess".equals(name) || "onFailure".equals(name)) {
                    ui.post(() -> {
                        if (ticket != serviceGeneration || !startingService) return;
                        startingService = false;
                        try {
                            XposedHelpers.callMethod(manager, "initialize"); updateNativeState();
                            if ("onSuccess".equals(name) && Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isInitialized"))) action.run();
                            else event("extension_service_failed", "Samsung's native extension service did not initialize.");
                        } catch (Throwable e) { event("extension_service_failed", shortError(e)); }
                    });
                    return null;
                }
                if ("hashCode".equals(name)) return System.identityHashCode(obj);
                if ("equals".equals(name)) return args != null && obj == args[0];
                return "InternetTools-extension-service-callback";
            });
            Object jni = singletonNative(MANAGER + "Jni");
            XposedHelpers.callMethod(jni, "initExtensions", pointer, manager, proxy);
            ui.postDelayed(() -> {
                if (ticket != serviceGeneration || !startingService) return;
                startingService = false; serviceGeneration++; updateNativeState();
                event("extension_service_timeout", "Samsung did not report extension-service startup within eight seconds.");
            }, 8000);
        } catch (Throwable e) { startingService = false; updateNativeState(); event("extension_service_failed", shortError(e)); }
    }

    private Object singletonNative(String name) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(name, cl), "get");
    }

    private Object singleton(String name) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(name, cl), "getInstance");
    }

    private boolean isInstalled() { return Boolean.TRUE.equals(XposedHelpers.callMethod(singleton(UTIL), "isInstalledInNative", ID)); }

    private Object readyLoader() {
        Object loader = singleton(LOADER);
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(loader, "initialized")))
            throw new IllegalStateException("Samsung's native extension loader did not initialize. Force stop and reopen the browser after updating the module.");
        return loader;
    }

    private void install() {
        if (installing || copying) { event("install_busy", "A uBO installation is already in progress."); return; }
        try {
            readyLoader();
            final String installRequest = requestId;
            if (isInstalled() && currentRuntimeInstalled()) { afterInstall(); return; }
            copying = true;
            event("copying_verified_crx", "Copying the independently signed uBO compatibility CRX into the browser's private files.");
            new Thread(() -> {
                File output = null;
                Throwable failure = null;
                try {
                    File dir = new File(app.getFilesDir(), "internet_private_ubo");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create browser-private staging directory");
                    output = new File(dir, "ublock-origin-1.75.0.crx");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (ZipFile apk = new ZipFile(modulePath);
                         InputStream in = apk.getInputStream(apk.getEntry("assets/ublock-origin.crx"));
                         FileOutputStream out = new FileOutputStream(output)) {
                        if (in == null) throw new IllegalStateException("Bundled CRX was unavailable");
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); digest.update(buffer, 0, n); }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    if (!SHA256.equals(hex.toString())) { output.delete(); throw new SecurityException("Bundled CRX hash did not match the verified compatibility package"); }
                } catch (Throwable e) { failure = e; }
                final File crx = output; final Throwable error = failure;
                ui.post(() -> {
                    copying = false;
                    if (!operationActive || !installRequest.equals(requestId)) return;
                    if (error != null) { event("copy_failed", shortError(error)); return; }
                    try {
                        installing = true;
                        // Samsung's existing standalone-CRX route uses is_thru_six_pkg=false.
                        // The compatibility-build signature is still checked by the native installer.
                        boolean accepted = Boolean.TRUE.equals(XposedHelpers.callMethod(readyLoader(), "launchInstallDefaultCrx", ID, crx.getAbsolutePath()));
                        if (!accepted) { installing = false; event("install_not_started", "Samsung's native CRX installer did not accept the installation request."); }
                        else if (installing) {
                            event("install_requested", "Installation requested. Waiting for Samsung's native success/failure callback; filtering is not confirmed.");
                            ui.postDelayed(() -> {
                                if (!installing || !operationActive || !installRequest.equals(requestId)) return;
                                installing = false;
                                try { if (isInstalled()) afterInstall(); else event("install_timeout", "No native success/failure callback and no uBO registry entry after 30 seconds."); }
                                catch (Throwable e) { event("install_status_failed", shortError(e)); }
                            }, 30000);
                        }
                    } catch (Throwable e) { installing = false; event("install_failed", shortError(e)); }
                });
            }, "InternetTools-uBO-copy").start();
        } catch (Throwable e) { event("install_unavailable", shortError(e)); }
    }

    private void afterInstall() {
        if (!operationActive || !"install".equals(activeCommand)) return;
        try {
            updateNativeState();
            if (!isInstalled()) { event("install_callback_without_registry", "No native uBO registry entry exists."); return; }
            activateRuntime();
        } catch (Throwable e) { updateNativeState(); event("post_install_failed", shortError(e)); }
    }

    private void activateRuntime() {
        NativeActivation.request(new NativeActivation.Backend(){
            public boolean enabled(){return Boolean.TRUE.equals(XposedHelpers.callMethod(singleton(UTIL),"isEnabledInNative",ID));}
            public void revive(){XposedHelpers.callMethod(readyLoader(),"reviveTerminatedExtIfExist",ID);}
            public void enable(){XposedHelpers.callMethod(readyLoader(),"changeExtensionStatus",ID,true);}
        });
        waitUntilEnabled(0);
    }

    private void waitUntilEnabled(int attempt) {
        if (!operationActive || !("install".equals(activeCommand)||"runtime".equals(activeCommand)||"runtime_open".equals(activeCommand))) return;
        final String enablingRequest=requestId;
        try {
            updateNativeState();
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(singleton(UTIL), "isEnabledInNative", ID))) {
                Object util = singleton(UTIL);
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(util, "canBeSecretModeEnabled", ID)) && !Boolean.TRUE.equals(XposedHelpers.callMethod(util,"getSecretModeState",ID)))
                    XposedHelpers.callMethod(util, "updateSecretModeState", ID, true);
                if(Boolean.TRUE.equals(XposedHelpers.callMethod(util,"isEnabledInNative",ExtensionConfig.LEGACY_ID)))
                    XposedHelpers.callMethod(readyLoader(),"changeExtensionStatus",ExtensionConfig.LEGACY_ID,false);
                if(!runtimeMarker().isFile()&&!runtimeMarker().createNewFile())throw new java.io.IOException("Runtime marker could not be saved");
                if("runtime_open".equals(activeCommand)){openDashboard(false);return;}
                if(!diagnosticMode){event("extension_enabled","uBlock Origin is enabled.");return;}
                prepareInspectionReturn();event("runtime_inspection_ready","Compatibility build enabled; running its live filtering test.");return;
            }
            if (attempt < 40) { ui.postDelayed(() -> {if(enablingRequest.equals(requestId))waitUntilEnabled(attempt + 1);}, 250); return; }
            event("extension_disabled", "uBO is registered, but Samsung did not enable it. It is not running.");
        } catch (Throwable e) { event("enable_failed", shortError(e)); }
    }

    private void openAndVerify() { openDashboard(true); }

    static boolean legacyDashboard(String url) {
        String expected = "chrome-extension://" + ID + "/dashboard.html";
        return url != null && (url.equals(expected) || url.startsWith(expected + "#"));
    }

    private void dismissOwnPopup() {
        Object owner = popupOwner, terrace = popupTerrace;
        popupOwner = null; popupTerrace = null;
        if (owner != null && terrace != null && ownExtension(terrace)) {
            try { XposedHelpers.callMethod(owner, "dismissPopup"); } catch (Throwable ignored) { }
        }
    }

    private void openDashboard(boolean verify) {
        if (!operationActive) return;
        try {
            updateNativeState();
            if (!isInstalled()) { event("not_installed", "uBO is not registered. Use Install first."); return; }
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(singleton(UTIL), "isEnabledInNative", ID))) {
                event("extension_disabled", "uBO is registered but disabled. Use Install to request enabling it."); return;
            }
            XposedHelpers.callMethod(mainView, "setSecretModeEnabled", false);
            int generation = ++probeGeneration;
            verifying = verify; waitingForPopup = false;
            if (popupTerrace != null && ownExtension(popupTerrace)) {
                useNativeView(generation, popupTerrace, verify); return;
            }
            event("opening_extension", "Waiting for Samsung's browser window before opening uBO's native extension view.");
            preparePopupHost(generation, 0);
        } catch (Throwable e) { verifying = false; event("extension_view_failed", shortError(e)); }
    }

    private void preparePopupHost(int generation, int retry) {
        if (generation != probeGeneration) return;
        try {
            Activity activity = controlActivity.get();
            Object helper = singleton("com.sec.terrace.TerraceHelper");
            if (activity == null || activity.isFinishing()) throw new IllegalStateException("Browser activity is unavailable");
            // The native popup constructor binds to the current TerraceActivity; its launch rejects a changed owner.
            if (!activity.hasWindowFocus() || XposedHelpers.callMethod(helper, "getCurrentTerraceActivity") != activity) {
                if (retry < 30) { ui.postDelayed(() -> preparePopupHost(generation, retry + 1), 200); return; }
                throw new IllegalStateException("The browser window did not become the current focused Terrace activity");
            }
            Object tab = XposedHelpers.callMethod(mainView, "getCurrentTab");
            Object terrace = tab == null ? null : XposedHelpers.callMethod(tab, "getTerrace");
            if (terrace == null) {
                Class<?> launch = XposedHelpers.findClass("com.sec.android.app.sbrowser.common.model.sbrowser_tab.TabLaunchType", cl);
                @SuppressWarnings({"rawtypes", "unchecked"}) Object fromUi = Enum.valueOf((Class)launch, "FROM_UI");
                XposedHelpers.callMethod(mainView, "loadUrlWithNewTab", "about:blank", null, false, false, fromUi, false);
                if (retry < 30) { ui.postDelayed(() -> preparePopupHost(generation, retry + 1), 200); return; }
                throw new IllegalStateException("No host tab became available");
            }
            String url = String.valueOf(XposedHelpers.callMethod(terrace, "getUrl"));
            if (legacyDashboard(url)) {
                // Recover only the exact options tab opened by 0.4.2, which Samsung blocks.
                Object params = XposedHelpers.newInstance(XposedHelpers.findClass("com.sec.terrace.browser.TerraceLoadUrlParams", cl), "about:blank");
                XposedHelpers.callMethod(terrace, "loadUrl", params);
                ui.postDelayed(() -> preparePopupHost(generation, retry + 1), 300); return;
            }
            if (url.startsWith("chrome-extension://")) throw new IllegalStateException("Select a normal browser tab before opening uBO");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(terrace, "isLoading")) && retry < 30) {
                ui.postDelayed(() -> preparePopupHost(generation, retry + 1), 200); return;
            }
            waitingForPopup = true;
            XposedHelpers.callMethod(singleton(MANAGER), "executeAction", ID);
            ui.postDelayed(() -> {
                if (generation != probeGeneration || !waitingForPopup) return;
                waitingForPopup = false; verifying = false;
                event("extension_view_failed", "Samsung did not expose a committed uBO extension view after its action was invoked from the focused browser window.");
            }, 10000);
        } catch (Throwable e) { waitingForPopup = false; verifying = false; event("extension_view_failed", shortError(e)); }
    }

    private boolean ownExtension(Object terrace) {
        try {
            // The visible URL can still show an extension URL on a Chromium error page.
            Object contents = XposedHelpers.callMethod(terrace, "getWebContents");
            Object committed = XposedHelpers.callMethod(contents, "getLastCommittedUrl");
            return String.valueOf(XposedHelpers.callMethod(committed, "getSpec")).startsWith("chrome-extension://" + ID + "/");
        } catch (Throwable e) { return false; }
    }

    private void trackPopup(Object owner, int retry) {
        try {
            Object terrace = XposedHelpers.getObjectField(owner, "mExtensionsTerrace");
            if (terrace != null && ownExtension(terrace)) {
                if(popupOwner!=owner)BrowserTrace.add("own_popup_committed");
                popupOwner = owner; popupTerrace = terrace;
                if (waitingForPopup) {
                    waitingForPopup = false;
                    useNativeView(probeGeneration, terrace, verifying);
                }
                return;
            }
            if (retry < 60) ui.postDelayed(() -> trackPopup(owner, retry + 1), 150);
        } catch (Throwable ignored) { }
    }

    private void useNativeView(int generation, Object terrace, boolean verify) {
        if (generation != probeGeneration || !ownExtension(terrace)) return;
        if ("undo_fix".equals(activeCommand)) { beginManagedRepair(generation, terrace, null, true); return; }
        // Open within the actual native popup; Samsung blocks the normal-tab route.
        if("runtime_open".equals(activeCommand)||"open".equals(activeCommand)){navigateNativeDashboard(generation,terrace);return;}
        // Legacy verification remains bounded and separate from dashboard navigation.
        ui.postDelayed(() -> runPageProbe(generation, terrace), 700);
    }

    private void navigateNativeDashboard(int generation, Object terrace) {
        if(generation!=probeGeneration||!operationActive||!ownExtension(terrace))return;
        // dashboard.html is now the declared native popup. Do not replace its WebContents.
        waitForNativeDashboard(generation,terrace,0);
    }

    private void waitForNativeDashboard(int generation, Object terrace, int retry) {
        if (generation != probeGeneration) return;
        try {
            if (ownExtension(terrace) && legacyDashboard(String.valueOf(XposedHelpers.callMethod(terrace, "getUrl")))
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(terrace, "isLoading"))) {
                evaluate(terrace, "(()=>{const blocked=!!document.querySelector('#main-frame-error, #error-page, .neterror');const bindings=typeof chrome==='object'&&!!chrome.runtime&&typeof chrome.runtime.getManifest==='function';const dashboard=globalThis.__internetToolsDashboardReady===true;return JSON.stringify({state:blocked?'extension_page_blocked':bindings&&dashboard?'dashboard_opened':'dashboard_initializing',mainWorldRuntime:bindings,dashboardReady:dashboard});})()", value -> {
                    if (generation != probeGeneration) return;
                    try {
                        Object decoded = new org.json.JSONTokener(value).nextValue();
                        JSONObject result = decoded instanceof String ? new JSONObject((String)decoded) : (JSONObject)decoded;
                        if("dashboard_initializing".equals(result.optString("state"))&&retry<120){ui.postDelayed(()->waitForNativeDashboard(generation,terrace,retry+1),250);return;}
                        event(result.optString("state", "dashboard_failed"), result.toString(2));
                    } catch (Throwable e) { event("dashboard_failed", shortError(e)); }
                }); return;
            }
            if (retry < 120) ui.postDelayed(() -> waitForNativeDashboard(generation, terrace, retry + 1), 250);
            else event("dashboard_failed", "The native uBO extension renderer did not finish loading its dashboard.");
        } catch (Throwable e) { event("dashboard_failed", shortError(e)); }
    }

    static boolean shouldReloadRuntime(JSONObject result, boolean alreadyAttempted) {
        return !alreadyAttempted && "runtime_incomplete".equals(result.optString("state"))
            && Boolean.FALSE.equals(result.opt("readyToFilter"));
    }

    private void reloadStalledRuntime(JSONObject result) {
        runtimeReloadAttempted = true; if (beforeReload == null) beforeReload = result;
        // A real native reload, once per explicit Install/Verify. No data clearing or forced ready flag.
        int generation = ++probeGeneration;
        verifying = false;
        try {
            dismissOwnPopup();
            XposedHelpers.callMethod(readyLoader(), "reloadExtension", ID);
            event("restarting_extension", "uBO did not finish startup. Restarting only this extension once, preserving its settings.");
            ui.postDelayed(() -> {
                if (generation != probeGeneration) return;
                openAndVerify();
            }, 5000);
        } catch (Throwable e) { event("runtime_restart_failed", shortError(e)); }
    }

    private void runPageProbe(int generation, Object terrace) { runPageProbe(generation, terrace, 0); }

    private void runPageProbe(int generation, Object terrace, int retry) {
        if (generation != probeGeneration) return;
        if (!ownExtension(terrace)) { verifying = false; event("extension_context_lost", "The uBO document was replaced or closed before the probe could start."); return; }
        try {
            if (diagnosticMode && retry == 0) collectUboConsole(requestId);
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(terrace, "isLoading")) && retry < 12) {
                ui.postDelayed(() -> runPageProbe(generation, terrace, retry + 1), 250); return;
            }
            event("opening_dashboard", "Checking uBO in its native extension JavaScript world.");
            String script = readModuleAsset("ubo-probe.js");
            evaluate(terrace, script, result -> {
                if (generation != probeGeneration) return;
                if (result != null && result.contains("probe_started")) ui.postDelayed(() -> pollProbe(generation, terrace, 0), 400);
                else if (retry < 20) ui.postDelayed(() -> runPageProbe(generation, terrace, retry + 1), 250);
                else { verifying = false; event("extension_context_failed", "The native uBO view did not expose a live extension document."); }
            });
        } catch (Throwable e) { verifying = false; event("probe_failed", shortError(e)); }
    }

    private void pollProbe(int generation, Object terrace, int retry) {
        if (generation != probeGeneration) return;
        try {
            evaluate(terrace, readModuleAsset("ubo-poll.js"), result -> {
                if (generation != probeGeneration) return;
                try {
                    Object value = new org.json.JSONTokener(result).nextValue();
                    JSONObject obj = value instanceof String ? new JSONObject((String) value) : (JSONObject) value;
                    String state = obj.optString("state", "pending");
                    if ("pending".equals(state) && retry < 84) {
                        ui.postDelayed(() -> pollProbe(generation, terrace, retry + 1), 500); return;
                    }
                    verifying = false;
                    if ("runtime_ready".equals(state) && "open".equals(activeCommand)) {
                        navigateNativeDashboard(generation, terrace); return;
                    }
                    if (!"open".equals(activeCommand) && !managedRepairAttempted && StartupRepairPolicy.needsCache(obj)) {
                        initialManagedFailure = obj; beginManagedRepair(generation, terrace, obj, false); return;
                    }
                    if (!"open".equals(activeCommand) && StartupRepairPolicy.firstInstallNeedsReload(obj, initialManagedFailure, managedRepairState, firstInstallReloadAttempted)) {
                        firstInstallReloadAttempted = true; reloadStalledRuntime(obj); return;
                    }
                    if (!"open".equals(activeCommand) && shouldReloadRuntime(obj, runtimeReloadAttempted)) { reloadStalledRuntime(obj); return; }
                    obj.put("hookBuild", BridgeContract.BUILD);
                    if (managedRepairState != null) obj.put("managedCacheRepair", managedRepairState);
                    obj.put("firstInstallReloadAttempted", firstInstallReloadAttempted);
                    if (runtimeReloadAttempted) {
                        obj.put("nativeReloadAttempted", true);
                        if (beforeReload != null) obj.put("beforeReload", beforeReload);
                    }
                    event("pending".equals(state) ? "probe_timeout" : state, obj.toString(2));
                } catch (Throwable e) { event("probe_result_unreadable", "The native JavaScript callback did not contain a valid diagnostic result: " + shortError(e)); }
            });
        } catch (Throwable e) { event("probe_failed", shortError(e)); }
    }

    private void beginManagedRepair(int generation, Object terrace, JSONObject failure, boolean undo) {
        if (generation != probeGeneration || !operationActive) return;
        managedRepairAttempted = true;
        if (failure != null && beforeReload == null) beforeReload = failure;
        event("repairing_startup", undo ? "Removing only the startup cache created by Internet Tools." : "Initializing uBO's missing startup cache after a verified managed-storage stall.");
        try {
            String prefix = "globalThis.__internetToolsRepairMode='" + (undo ? "undo" : "repair") + "';\n";
            evaluate(terrace, prefix + readModuleAsset("ubo-managed-cache.js"), value -> {
                if (generation != probeGeneration) return;
                if (value != null && value.contains("repair_started")) ui.postDelayed(() -> pollManagedRepair(generation, terrace, undo, 0), 300);
                else event("startup_fix_failed", "The extension document did not accept the startup-cache operation.");
            });
        } catch (Throwable e) { event("startup_fix_failed", shortError(e)); }
    }

    private void pollManagedRepair(int generation, Object terrace, boolean undo, int retry) {
        if (generation != probeGeneration || !operationActive) return;
        try {
            String script="JSON.stringify(location.protocol==='chrome-extension:'&&location.hostname==='" + ID
                + "'?(globalThis.__internetToolsManagedRepair_v1||{state:'startup_fix_context_lost'}):{state:'startup_fix_origin_changed'})";
            evaluate(terrace, script, value -> {
                if (generation != probeGeneration) return;
                try {
                    Object decoded=new org.json.JSONTokener(value).nextValue();
                    JSONObject result=decoded instanceof String?new JSONObject((String)decoded):(JSONObject)decoded;
                    String state=result.optString("state");
                    if ("pending".equals(state)&&retry<32) {ui.postDelayed(()->pollManagedRepair(generation,terrace,undo,retry+1),500);return;}
                    managedRepairState=result;
                    if (!undo && ("managed_cache_initialized".equals(state)||"managed_cache_already_present".equals(state))) {
                        reloadStalledRuntime(beforeReload == null ? result : beforeReload); return;
                    }
                    if (!undo && "startup_fix_not_applicable".equals(state)) {runPageProbe(generation,terrace);return;}
                    event("pending".equals(state)?"startup_fix_timeout":state,result.toString(2));
                } catch (Throwable e) {event("startup_fix_failed",shortError(e));}
            });
        } catch (Throwable e) {event("startup_fix_failed",shortError(e));}
    }

    private interface Result { void receive(String value); }
    private void evaluate(Object terrace, String script, Result result) {
        final int generation = probeGeneration;
        final EvaluationDeadline deadline = new EvaluationDeadline(ui,
            () -> generation == probeGeneration && operationActive,
            () -> event("javascript_callback_timeout", "Samsung did not return a JavaScript callback within three seconds."));
        Class<?> callback = XposedHelpers.findClass("com.sec.terrace.TerraceJavaScriptCallback", cl);
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, (obj, method, args) -> {
            if ("handleJavaScriptResult".equals(method.getName()) && args != null && args.length == 1) {
                if (!deadline.complete()) return null;
                result.receive(String.valueOf(args[0])); return null;
            }
            if ("toString".equals(method.getName())) return "InternetTools-uBO-callback";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(obj);
            if ("equals".equals(method.getName())) return args != null && obj == args[0];
            return null;
        });
        // Explicit WebContents main-world execution, rather than Terrace's general native evaluation path.
        XposedHelpers.callMethod(terrace, "evaluateJavaScriptForTests", script, proxy);
    }

    private String readModuleAsset(String name) throws Exception {
        try (ZipFile apk = new ZipFile(modulePath);
             InputStream in = apk.getInputStream(apk.getEntry("assets/" + name));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void remove() {
        probeGeneration++;
        if (copying || installing) { event("remove_busy", "Wait for the current installation attempt before removing uBO."); return; }
        try {
            if (!isInstalled()) { event("not_installed", "uBO is not installed."); return; }
            boolean accepted = Boolean.TRUE.equals(XposedHelpers.callMethod(readyLoader(), "launchUninstallCrx", ID));
            event(accepted ? "remove_requested" : "remove_not_started", accepted ? "Removal requested; waiting for the native callback." : "Native installer did not accept removal.");
        } catch (Throwable e) { event("remove_failed", shortError(e)); }
    }

    private void collectUboConsole(String commandId) {
        consoleReport = "Capturing matching uBO console lines";
        new Thread(() -> {
            String result;
            Process process = null;
            try {
                process = new ProcessBuilder("logcat", "-d", "-t", "2000", "--pid", String.valueOf(android.os.Process.myPid()), "-v", "brief").redirectErrorStream(true).start();
                final Process running = process;
                ui.postDelayed(() -> { try { running.destroy(); } catch (Throwable ignored) { } }, 3000);
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line; int read = 0;
                    while ((line = reader.readLine()) != null && ++read <= 2200) {
                        String safe = ConsolePolicy.uboLine(line);
                        if (safe == null) continue;
                        if (lines.size() >= 24) lines.remove(0);
                        lines.add(safe);
                    }
                }
                result = lines.isEmpty() ? "No matching uBO source lines visible in the browser process. Renderer logs may not be accessible." : android.text.TextUtils.join("\n", lines);
            } catch (Throwable e) { result = "Console capture unavailable: " + e.getClass().getSimpleName(); }
            finally { if (process != null) process.destroy(); }
            final String captured = result;
            ui.post(() -> {
                if (!commandId.equals(requestId)) return;
                consoleReport = captured;
                try {
                    if (reply != null) reply.send(app, 0, new Intent().putExtra(BridgeContract.REQUEST, commandId)
                        .putExtra("metadataOnly", true).putExtra("uboConsole", captured).putExtra("hookBuild", BridgeContract.BUILD));
                } catch (Throwable ignored) { }
            });
        }, "InternetTools-console").start();
    }

    private void event(String state, String detail) {
        if (app == null || !operationActive) return;
        if ("opening_dashboard".equals(state) && !checkToastShown) {
            checkToastShown = true;
            android.widget.Toast.makeText(app, "Checking uBO…", android.widget.Toast.LENGTH_SHORT).show();
        }
        BrowserTrace.add("command_"+state);
        boolean terminal = !OperationState.busy(state);
        if (terminal) {
            operationActive = false; pendingCommand = null; waitingForPopup = false; verifying = false;
            probeGeneration++; serviceGeneration++; startingService = false;
        }
        try {
            Bundle b = new Bundle(); b.putString("state", state); b.putString("detail", bounded(detail));
            if (reply != null) {
                Intent result = new Intent();
                result.putExtra("hookBuild", BridgeContract.BUILD).putExtra("browserPid",android.os.Process.myPid());
                if("runtime_inspection_ready".equals(state)&&inspectionReturn!=null)result.putExtra("inspectionReturn",inspectionReturn);
                if (managedRepairState != null) result.putExtra("managedRepair", managedRepairState.toString());
                result.putExtra("state", state).putExtra("detail", bounded(detail)).putExtra("nativeState", nativeState);
                result.putExtra(BridgeContract.REQUEST, requestId);
                if (beforeReload != null) result.putExtra("startupState", beforeReload.toString());
                result.putExtra("uboConsole", consoleReport);
                reply.send(app, 0, result);
            } else app.getContentResolver().call(CONTROL, "uboEvent", null, b);
        } catch (Throwable e) { XposedBridge.log("InternetTools: uBO state=" + state + " (status provider unavailable)"); }
        if(terminal&&"runtime_inspection_ready".equals(state)&&inspectionReturn!=null)return;
        if (terminal && !("open".equals(activeCommand)||"runtime_open".equals(activeCommand))) returnToCompanion();
    }

    private void prepareInspectionReturn(){
        final String action="dev.dov.internettools.INSPECTION_FINISHED";
        if(!returnReceiverRegistered){
            android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){
                @Override public void onReceive(Context context,Intent intent){
                    if(inspectionReturnId==null||!inspectionReturnId.equals(intent.getStringExtra(BridgeContract.REQUEST)))return;
                    confirmRuntimeBuild(intent);
                    inspectionReturnId=null;if(inspectionReturn!=null){inspectionReturn.cancel();inspectionReturn=null;}
                    // returnToCompanion checks that the initiating Samsung window is still resumed.
                    if(!intent.getBooleanExtra("keepBrowser",false))returnToCompanion();
                }
            };
            android.content.IntentFilter filter=new android.content.IntentFilter(action);
            if(android.os.Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(receiver,filter);
            returnReceiverRegistered=true;
        }
        inspectionReturnId=requestId;
        inspectionReturn=PendingIntent.getBroadcast(app,99,new Intent(action).setPackage(LinkPolicy.BROWSER).putExtra(BridgeContract.REQUEST,requestId),
            PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);
    }

    private void returnToCompanion() {
        Activity activity = controlActivity.get();
        if (activity == null || activity.isFinishing()) return;
        try {
            Class<?> status = XposedHelpers.findClass("com.sec.terrace.TerraceApplicationStatus", cl);
            if (!Integer.valueOf(3).equals(XposedHelpers.callStaticMethod(status, "getStateForActivity", activity))) return;
            dismissOwnPopup();
            if(returnToApp!=null){returnToApp.send();return;}
            activity.startActivity(new Intent().setClassName("dev.dov.internettools", "dev.dov.internettools.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Throwable e) { XposedBridge.log("InternetTools: result saved; return to Internet Tools to view it"); }
    }

    private static boolean ours(Object[] args) {
        for (Object arg : args) if (ID.equals(arg)) return true;
        return false;
    }
    private static String bounded(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 5000)); }
    private static String shortError(Throwable e) {
        Throwable cause = e;
        for (int i=0; i<5 && cause.getCause()!=null; i++) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + bounded(cause.getMessage());
    }
}
