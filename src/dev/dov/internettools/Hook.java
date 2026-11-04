package dev.dov.internettools;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static String modulePath;
    @Override public void initZygote(StartupParam startup) { modulePath = startup.modulePath; }
    private static final String P = "com.sec.android.app.sbrowser.";
    private static final String STATUS = "content://dev.dov.internettools.status";
    private final StringBuilder missing = new StringBuilder();
    private int installed;
    private Context app;
    private UboInstaller ubo;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!LinkPolicy.BROWSER.equals(lp.packageName)) return;
        final ClassLoader cl = lp.classLoader;
        installPrivacy(cl);
        installLinks(cl);
        ubo = new UboInstaller(this, cl, LinkPolicy.BROWSER.equals(lp.processName), modulePath);
        hook(cl, "com.sec.terrace.TerraceHelper", "initializeAfterBrowserStartupCompleted", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if(ubo!=null)ubo.onBrowserReady();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { report(lp.processName); }
                }, 1500);
            }
        });
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                app = (Context) p.args[0];
                BrowserSettings.attach(app);
                ubo.attach(app);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { report(lp.processName); }
                }, 2000);
            }
        });
        XposedBridge.log("InternetTools: " + lp.processName + ": " + installed
            + " hooks installed; missing=" + missing);
    }

    private void installPrivacy(ClassLoader cl) {
        stop(cl, P + "SALoggingInitializer", "initialize", "initializeInternal", "initializeStatusLogs");
        stop(cl, P + "common.logging.SALogging", "sendEventLog", "sendEventLogInternal",
             "sendEventLogWithoutScreenID", "sendStatusLog", "sendWeeklyCountStatusLog", "registerStatusPreference");
        stop(cl, P + "common.logging.AppLogging", "insertLog", "insertStatusLog", "insertLogInternal");
        stop(cl, "com.sec.terrace.browser.TinSALogging", "sendEventLog", "sendEventLogDimension",
             "sendEventLogWithDetail", "sendStatusLog");
        stop(cl, P + "si_log.SILog", "initialize", "send", "sendInternal", "sendStatus", "sendStatusLogInternal");
        stop(cl, P + "si_log.model.SILogRepository", "sendSILog", "sendSIStatusLogs", "insertSILogHistory");
        constant(cl, P + "common.device.DeviceSettings", "getEnableSALogging", false);
        constant(cl, P + "common.device.SBrowserFeatures", "isSurveyModeEnabled", false);

        stop(cl, P + "donations.DeepSkyDonationHelper", "donation", "sendUrl", "sendUrlImpl");
        constant(cl, P + "donations.DeepSkyDonationHelper", "isDonationAvailable", false);
        constant(cl, P + "donations.AppSearchDonationHelper", "isDonationAvailable", false);
        stop(cl, P + "donations.AppSearchDonationHelper", "donateTabs", "putDocumentsRequest");
        stop(cl, P + "handoff.HandoffHelper", "sendUrl", "sendWebState", "sendData");
        stop(cl, P + "artificial_intelligence.ai_brief.AiBriefHandler", "sendReadArticlesAloud");

        // No writes to account settings: these changes last only for the hooked process.
        constant(cl, P + "common.sync.SyncUtil", "isInternetSyncTurnedOn", false);
        constant(cl, P + "common.sync.SyncUtil", "isOpenTabsOptionTurnedOn", false);
        stop(cl, P + "common.sync.SyncUtil", "requestSync", "requestLocalSync", "enableSiSync");
        constant(cl, P + "common.sync.CloudSyncController", "isSyncAutomaticallyEnabled", false);
        constant(cl, P + "common.sync.CloudSyncController", "isContentSyncEnabled", false);
        stop(cl, P + "common.sync.CloudSyncController", "requestContentSync", "requestSyncByPush", "retrySync");

        // Samsung's factory already provides a safe empty shopping controller.
        constant(cl,P+"common.kindred.KindredUtil","isSmartShoppingSupported",false);
        hook(cl,P+"kindred.KindredControllerFactory","createKindredController",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){if(BrowserSettings.privacy&&p.args.length>0&&p.args[0] instanceof Boolean)p.args[0]=false;}
        });
        stop(cl,P+"main_view.MainViewShoppingAssistant","startKindred");
        stop(cl,P+"kindred.KindredController","onLoadFinished","onLoadStarted","onCurrentTabChanged","postUpdateJavaScriptInterface","updateJavaScriptInterface");
        stop(cl,P+"kindred.service.KindredService","trackCouponEvents");
        constant(cl,P+"common.sync.CloudSyncController","isSyncable",false);
        stop(cl,P+"common.sync.CloudSyncController","requestSyncWithTrigger");
        stop(cl,P+"common.sync.SyncUtil","requestSyncForColdStart","requestBrowserSyncNowForSiCommandLine");
        stop(cl,P+"common.bixby.BixbyAppStateHelper","updateAppState");
        stop(cl,P+"artificial_intelligence.read_aloud.ReadArticlesAloudRecommender","sendAiToBriefByLaunch","sendAiToBriefByRecommend");

        // Keep browser's own database access; reject the provider's external allowlist.
        hook(cl, P + "sites.provider.SBrowserProviderPermissionManager", "checkAllowedSet", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if(BrowserSettings.privacy)p.setResult(Binder.getCallingUid() == Process.myUid());
            }
        });

        // Moneta keyword handoff uses ContentResolver.insert, including a synthetic caller.
        XposedHelpers.findAndHookMethod(android.content.ContentResolver.class, "insert",
            Uri.class, android.content.ContentValues.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Uri uri = (Uri) p.args[0];
                    if (BrowserSettings.privacy && uri != null && "com.samsung.android.moneta.datacollector.reception.external.data".equals(uri.getAuthority()))
                        p.setResult(null);
                }
            });
        installed++;
    }

    private void installLinks(ClassLoader cl) {
        hook(cl,P+"common.secret_mode.SecretModeSettings","isOpenInSecretModeFromOtherApps",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){if(BrowserSettings.normalLinks)p.setResult(false);}
        });
        hook(cl, P + "secret_mode.SecretModeManager", "shouldOpenInSecretMode", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 1 && p.args[0] instanceof Activity) {
                    Activity activity = (Activity)p.args[0];
                    if ((BrowserSettings.normalLinks && external(activity.getIntent())) || (ubo != null && ubo.isControlActivity(activity))) p.setResult(false);
                }
            }
        });
        hook(cl, P + "main_view.intent.IntentHandler", "disableSecretModeIfNeeded", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!BrowserSettings.normalLinks || p.args.length != 2 || !(p.args[0] instanceof Intent) || !external((Intent) p.args[0])) return;
                Intent intent = (Intent) p.args[0];
                normalExtras(intent);
                p.args[1] = false;
                // Use the real mode-switch operation; never unlock private tabs or fake authentication.
                Object main = XposedHelpers.getObjectField(p.thisObject, "mMainViewInterface");
                XposedHelpers.callMethod(main, "setSecretModeEnabled", false);
            }
        });
        hook(cl, P + "main_view.intent.SecretModeIntentHandler", "loadUrlWithExistingTab", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!BrowserSettings.normalLinks || p.args.length != 4 || !(p.args[1] instanceof Intent) || !external((Intent) p.args[1])) return;
                Intent intent = (Intent) p.args[1];
                normalExtras(intent);
                Object delegate = XposedHelpers.getObjectField(p.thisObject, "mNewTabHandler");
                XposedHelpers.callMethod(delegate, "loadUrlWithNewTabFromExternalApp", p.args[2], intent, false, false);
                p.setResult(null);
            }
        });
        hook(cl, P + "main_view.MainViewImpl", "loadUrlWithNewTabFromExternalApp", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!BrowserSettings.normalLinks || p.args.length != 4 || !(p.args[1] instanceof Intent) || !external((Intent) p.args[1])) return;
                normalExtras((Intent) p.args[1]);
                p.args[2] = false;
                XposedHelpers.callMethod(p.thisObject, "setSecretModeEnabled", false);
            }
        });
    }

    private static boolean external(Intent i) {
        return i != null && LinkPolicy.isExternalWebLink(i.getAction(), i.getScheme(),
            i.getStringExtra("com.android.browser.application_id"),
            i.getBooleanExtra("terrace-image-or-link-drag-label", false),
            i.getBooleanExtra("com.samsung.intent.extra.OTHER_WINDOW", false));
    }

    private static void normalExtras(Intent i) {
        i.removeExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB");
        i.removeExtra("show_tab_without_loading");
    }

    private void stop(ClassLoader cl, String name, String... methods) {
        for (String method : methods) hook(cl, name, method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Avoid returning null for coroutine or builder APIs with non-void contracts.
                if (BrowserSettings.privacy && ((Method) p.method).getReturnType() == void.class) p.setResult(null);
            }
        });
    }

    private void constant(ClassLoader cl, String name, String method, final boolean value) {
        hook(cl, name, method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (BrowserSettings.privacy && ((Method) p.method).getReturnType() == boolean.class) p.setResult(value);
            }
        });
    }

    void hook(ClassLoader cl, String name, String method, XC_MethodHook callback) {
        try {
            Class<?> type = XposedHelpers.findClass(name, cl);
            int n = 0;
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals(method) && !Modifier.isAbstract(m.getModifiers())) {
                    XposedBridge.hookMethod(m, callback);
                    installed++; n++;
                }
            }
            if (n == 0) noteMissing(name, method);
        } catch (Throwable t) {
            noteMissing(name, method);
        }
    }

    private void noteMissing(String name, String method) {
        if (missing.length() > 0) missing.append('\n');
        missing.append(name).append('#').append(method);
    }

    private void report(String process) {
        if (app == null) return;
        try {
            Bundle b = new Bundle();
            b.putString("process", process);
            b.putString("hookBuild", BridgeContract.BUILD);
            b.putInt("browserPid",Process.myPid());
            b.putInt("installed", installed);
            b.putString("missing", missing.toString());
            b.putString("browserVersion", app.getPackageManager().getPackageInfo(LinkPolicy.BROWSER, 0).versionName);
            String capabilities = "Not checked outside the main browser process";
            if (LinkPolicy.BROWSER.equals(process)) {
                try {
                    ClassLoader cl = app.getClassLoader();
                    Object helper = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.sec.terrace.TerraceHelper", cl), "getInstance");
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isInitialized"))) {
                        boolean enabled = (Boolean) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.sec.terrace.TerraceFeatureList", cl), "isEnabled", "EnableExtensions");
                        capabilities = "Extension feature enabled: " + enabled;
                        if (enabled) {
                            Object loader = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                                "com.sec.terrace.browser.extensions.TerraceExtensionsLoader", cl), "getInstance");
                            capabilities += "\nNative extension loader initialized: " + XposedHelpers.callMethod(loader, "initialized");
                        }
                    } else capabilities = "Browser engine was not initialized when reported";
                } catch (Throwable t) { capabilities = "Extension capability probe unavailable"; }
            }
            b.putString("capabilities", capabilities);
            app.getContentResolver().call(Uri.parse(STATUS), "report", null, b);
        } catch (Throwable t) {
            XposedBridge.log("InternetTools: status unavailable; hook setup remains in LSPosed log");
        }
    }
}
