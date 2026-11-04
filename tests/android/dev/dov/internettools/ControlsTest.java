package dev.dov.internettools;

import static org.junit.Assert.*;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ControlsTest {
    @org.junit.Before public void noRootCollectorInJvm(){DiagnosticSession.disabledForTests=true;}
    @Test public void fourControlsSendDifferentDirectCommandsAndNoWebsite() throws Exception {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        int[] ids={R.id.install_ubo,R.id.open_ubo,R.id.verify_ubo,R.id.remove_ubo};
        String[] commands={"install","runtime_open","runtime","remove"};
        for(int n=0;n<ids.length;n++) {
            activity.getSharedPreferences("bridge",0).edit().clear().commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.findViewById(ids[n]) instanceof MaterialButton);
            assertTrue(activity.findViewById(ids[n]).performClick());
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertNull("Controls must never navigate to a website",intent.getData());
            assertEquals("com.sec.android.app.sbrowser.SBrowserMainActivity",intent.getComponent().getClassName());
            assertEquals(BridgeContract.ACTION,intent.getAction());
            assertEquals(commands[n],intent.getStringExtra(BridgeContract.COMMAND));
            assertTrue(BridgeContract.validEnvelope(intent));
            Intent tampered=new Intent(intent).putExtra(BridgeContract.COMMAND,"run_arbitrary_code");
            assertFalse(BridgeContract.validEnvelope(tampered));
            assertFalse(BridgeContract.validEnvelope(new Intent(BridgeContract.ACTION)));
            PendingIntent reply=intent.getParcelableExtra(BridgeContract.REPLY);
            reply.send(activity,0,new Intent().putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","install_rejected").putExtra("detail","Native test error").putExtra("nativeState","{\"registered\":true,\"enabled\":false}"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("install_rejected",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertTrue(activity.getSharedPreferences("bridge",0).getLong("ack",0)>0);
            assertEquals("{\"registered\":true,\"enabled\":false}",activity.getSharedPreferences("status",0).getString("Native extension",""));
        }
    }

    @Test public void missingAcknowledgementBecomesVisibleFailure() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        SharedPreferences p=activity.getSharedPreferences("bridge",0);
        p.edit().putString("state","connecting").putLong("ack",0).putLong("sent",100).commit();
        BridgeContract.expireUnacknowledged(p,100+BridgeContract.TIMEOUT_MS+1);
        assertEquals("no_response",p.getString("state",""));
        assertTrue(p.getString("detail","").contains("Restart & test"));
    }

    @Test public void failedViewKeepsTheStartupFailureThatTriggeredReload() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        activity.getSharedPreferences("bridge",0).edit().putString("request","current").putBoolean("active",true).putString("state","connecting").putLong("sent",System.currentTimeMillis()).commit();
        BridgeReceiver receiver=new BridgeReceiver();
        receiver.onReceive(activity,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","dashboard_failed")
            .putExtra("startupState","{\"readyToFilter\":false,\"startup\":{\"storageRead\":\"timeout\"}}"));
        assertTrue(activity.getSharedPreferences("status",0).getString("Startup before native reload","").contains("timeout"));
        activity.getSharedPreferences("bridge",0).edit().putBoolean("active",true).putString("state","connecting").putLong("sent",System.currentTimeMillis()).commit();
        receiver.onReceive(activity,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","received"));
        assertFalse(activity.getSharedPreferences("status",0).contains("Startup before native reload"));
    }

    @Test public void staleReplyCannotCompleteNewAction() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        SharedPreferences p=activity.getSharedPreferences("bridge",0);
        p.edit().putString("request","current").putBoolean("active",true).putString("state","connecting").putLong("sent",System.currentTimeMillis()).commit();
        new BridgeReceiver().onReceive(activity,new Intent().putExtra(BridgeContract.REQUEST,"previous").putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","runtime_ready"));
        assertEquals("connecting",p.getString("state",""));
    }

    @Test public void onlyTheLegacyOwnDashboardCanBeReplacedWithABlankHost() {
        String url="chrome-extension://odkabbfgkiaeajgdfcfhnbkmjjkolehf/dashboard.html";
        assertTrue(UboInstaller.legacyDashboard(url));
        assertTrue(UboInstaller.legacyDashboard(url+"#3p-filters.html"));
        assertFalse(UboInstaller.legacyDashboard("https://example.com/"));
        assertFalse(UboInstaller.legacyDashboard("chrome-extension://other/dashboard.html"));
        assertFalse(UboInstaller.legacyDashboard(url+"/other"));
        assertFalse(UboInstaller.legacyDashboard(null));
    }

    @Test public void consoleReportExcludesOtherSourcesAndRedactsWebsiteUrls() {
        assertNull(ConsolePolicy.uboLine("I chromium: https://private.example/path"));
        assertNull(ConsolePolicy.uboLine("I chromium: chrome-extension://other/js/start.js"));
        String line=ConsolePolicy.uboLine("E chromium: TypeError https://private.example/path SOURCE: chrome-extension://odkabbfgkiaeajgdfcfhnbkmjjkolehf/js/start.js:90");
        assertNotNull(line);
        assertTrue(line.contains("TypeError"));
        assertFalse(line.contains("private.example"));
        assertTrue(line.contains("js/start.js:90"));
    }

    @Test public void onlyStalledReadinessCanRequestOneNativeReload() throws Exception {
        org.json.JSONObject stalled=new org.json.JSONObject("{\"state\":\"runtime_incomplete\",\"readyToFilter\":false}");
        assertTrue(UboInstaller.shouldReloadRuntime(stalled,false));
        assertFalse(UboInstaller.shouldReloadRuntime(stalled,true));
        assertFalse(UboInstaller.shouldReloadRuntime(new org.json.JSONObject("{\"state\":\"runtime_incomplete\",\"readyToFilter\":true,\"networkFilterCount\":0}"),false));
        assertFalse(UboInstaller.shouldReloadRuntime(new org.json.JSONObject("{\"state\":\"probe_failed\"}"),false));
        assertFalse(UboInstaller.shouldReloadRuntime(new org.json.JSONObject("{\"state\":\"runtime_incomplete\"}"),false));
    }

    @Test public void acknowledgedRequestsAlsoExpireAndRejectLateResults() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        SharedPreferences p=activity.getSharedPreferences("bridge",0);
        for(String state:new String[]{"opening_extension","opening_dashboard","install_requested","restarting_extension","force_stopping"}) {
            p.edit().putString("request","current").putBoolean("active",true).putString("state",state).putLong("ack",1).putLong("deadline",100).commit();
            BridgeContract.expireUnacknowledged(p,101);
            assertEquals("operation_timeout",p.getString("state","")); assertFalse(p.getBoolean("active",true));
            new BridgeReceiver().onReceive(activity,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","runtime_ready"));
            assertEquals("operation_timeout",p.getString("state",""));
        }
    }

    private void finishRootWork(DiagnosticsActivity activity) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!activity.getSharedPreferences("bridge",0).getString("state","").equals("force_stopping")) return;
            Thread.sleep(5);
        }
        fail("Root operation did not settle");
    }

    @Test public void forceStopIsAvailableDuringAStuckCheckAndEndsLocally() throws Exception {
        BrowserControl.Stopper old=BrowserControl.stopper;
        try {
            final int[] calls={0};BrowserControl.stopper=()->{calls[0]++;return true;};
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.getSharedPreferences("bridge",0).edit().putString("state","opening_dashboard").putBoolean("active",true).putLong("deadline",System.currentTimeMillis()+5000).commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.findViewById(R.id.force_stop).isEnabled());
            activity.findViewById(R.id.force_stop).performClick();finishRootWork(activity);
            assertEquals(1,calls[0]);assertEquals("browser_stopped",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        } finally {BrowserControl.stopper=old;}
    }

    @Test public void restartAndTestLaunchesVerificationOnlyAfterStopSucceeds() throws Exception {
        BrowserControl.Stopper old=BrowserControl.stopper;
        try {
            BrowserControl.stopper=()->true;
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.findViewById(R.id.restart_test).performClick();finishRootWork(activity);
            Intent launch=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(launch);
            assertEquals("install",launch.getStringExtra(BridgeContract.COMMAND));
            assertEquals("connecting",activity.getSharedPreferences("bridge",0).getString("state",""));
        } finally {BrowserControl.stopper=old;}
    }

    @Test public void rootDenialDoesNotLaunchBrowserOrClaimItStopped() throws Exception {
        BrowserControl.Stopper old=BrowserControl.stopper;
        try {
            BrowserControl.stopper=()->false;
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.findViewById(R.id.restart_test).performClick();finishRootWork(activity);
            assertEquals("force_stop_failed",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        } finally {BrowserControl.stopper=old;}
    }

    @Test public void diagnosticsOpensImmediatelyWithCurrentReportWhileARequestIsBusy() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        activity.getSharedPreferences("bridge",0).edit().putString("state","opening_dashboard").putBoolean("active",true).putLong("deadline",System.currentTimeMillis()+5000).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.findViewById(R.id.diagnostics).performClick());
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog());
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing());
    }

    @Test public void consoleMetadataCanArriveAfterCompletionWithoutReopeningTheJob() {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        SharedPreferences p=activity.getSharedPreferences("bridge",0);
        p.edit().putString("request","current").putString("state","operation_timeout").putBoolean("active",false).commit();
        new BridgeReceiver().onReceive(activity,new Intent().putExtra(BridgeContract.REQUEST,"current")
            .putExtra("metadataOnly",true).putExtra("uboConsole","TypeError at extension source"));
        assertEquals("operation_timeout",p.getString("state",""));
        assertEquals("TypeError at extension source",activity.getSharedPreferences("status",0).getString("uBO console",""));
        assertFalse(p.getBoolean("active",true));
    }

    @Test public void missingNativeCallbackTimesOutOnceAndRejectsLateDelivery() {
        android.os.Handler handler=new android.os.Handler(Looper.getMainLooper());
        final int[] failures={0};
        EvaluationDeadline dropped=new EvaluationDeadline(handler,()->true,()->failures[0]++);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3001));
        assertEquals(1,failures[0]);assertFalse(dropped.complete());
        EvaluationDeadline delivered=new EvaluationDeadline(handler,()->true,()->failures[0]++);
        assertTrue(delivered.complete());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));
        assertEquals(1,failures[0]);
        new EvaluationDeadline(handler,()->false,()->failures[0]++);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));
        assertEquals(1,failures[0]);
    }

    @Test public void startupFixCanRequestOnlyOneExtraRootRestartInAnExplicitRootTestChain() {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        SharedPreferences p=context.getSharedPreferences("bridge",0);
        String fix="{\"state\":\"managed_cache_initialized\",\"changed\":true}";
        p.edit().putString("request","current").putBoolean("active",true).putString("state","opening_dashboard")
            .putBoolean("rootTestChain",true).putBoolean("startupRepairRestartUsed",false).putLong("sent",System.currentTimeMillis()).commit();
        new BridgeReceiver().onReceive(context,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD)
            .putExtra("state","runtime_incomplete").putExtra("managedRepair",fix));
        assertTrue(p.getBoolean("startupRepairRestartPending",false));
        p.edit().putBoolean("active",true).putString("state","opening_dashboard").putBoolean("startupRepairRestartUsed",true).putBoolean("startupRepairRestartPending",false).commit();
        new BridgeReceiver().onReceive(context,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD)
            .putExtra("state","runtime_incomplete").putExtra("managedRepair",fix));
        assertFalse(p.getBoolean("startupRepairRestartPending",false));
        p.edit().putBoolean("active",true).putString("state","opening_dashboard").putBoolean("rootTestChain",false).putBoolean("startupRepairRestartUsed",false).commit();
        new BridgeReceiver().onReceive(context,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD)
            .putExtra("state","runtime_incomplete").putExtra("managedRepair",fix));
        assertFalse(p.getBoolean("startupRepairRestartPending",false));
    }

    private void finishInspection(DiagnosticsActivity activity) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<until){
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!activity.getSharedPreferences("bridge",0).getString("state","").equals("checking_background"))return;
            Thread.sleep(5);
        }fail("Background inspection did not settle");
    }

    @Test public void directBackgroundInspectionCompletesWithoutOpeningAPopup() throws Exception {
        BackgroundInspector.Runner old=BackgroundInspector.runner;
        try{
            BackgroundInspector.runner=(mode,apk)->new org.json.JSONObject("{\"state\":\"runtime_ready\",\"networkFilterCount\":10000,\"requestBlockingVerified\":false}");
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.getSharedPreferences("bridge",0).edit().putString("acknowledgedHookBuild",BridgeContract.BUILD).commit();
            BackgroundInspector.start(activity,false);finishInspection(activity);
            assertEquals("runtime_ready",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertTrue(activity.getSharedPreferences("status",0).getString("Background runtime","").contains("10000"));
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }finally{BackgroundInspector.runner=old;}
    }

    @Test public void missingLocalDebuggerProducesATerminalFailure() throws Exception {
        BackgroundInspector.Runner old=BackgroundInspector.runner;
        try{
            BackgroundInspector.runner=(mode,apk)->{throw new java.io.IOException("socket_unavailable");};
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.getSharedPreferences("bridge",0).edit().putString("acknowledgedHookBuild",BridgeContract.BUILD).commit();
            BackgroundInspector.start(activity,false);finishInspection(activity);
            assertEquals("background_inspection_failed",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertFalse(activity.getSharedPreferences("bridge",0).getBoolean("active",true));
        }finally{BackgroundInspector.runner=old;}
    }

    @Test public void handshakeSchedulesInspectionWithoutKeepingTheBrowserJobBusy() {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();SharedPreferences p=context.getSharedPreferences("bridge",0);
        p.edit().putString("request","current").putBoolean("diagnosticRequest",true).putString("command","runtime").putString("state","connecting").putBoolean("active",true).putLong("sent",System.currentTimeMillis()).commit();
        new BridgeReceiver().onReceive(context,new Intent().putExtra(BridgeContract.REQUEST,"current").putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","runtime_inspection_ready"));
        assertTrue(p.getBoolean("backgroundInspectionPending",false));assertFalse(p.getBoolean("active",true));
    }

    @Test public void oldPendingInspectionCannotBypassTheFreshHookHandshake()throws Exception {
        BackgroundInspector.Runner old=BackgroundInspector.runner;int[] calls={0};
        try{
            BackgroundInspector.runner=(mode,apk)->{calls[0]++;return new org.json.JSONObject();};
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.getSharedPreferences("bridge",0).edit().putBoolean("backgroundInspectionPending",true).putString("acknowledgedHookBuild","0.6.0").commit();
            BackgroundInspector.start(activity,false);
            assertEquals(0,calls[0]);assertEquals("hook_update_required",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertFalse(activity.getSharedPreferences("bridge",0).getBoolean("backgroundInspectionPending",true));
        }finally{BackgroundInspector.runner=old;}
    }

    @Test public void inspectionCanRunWhileCompanionActivityHasNoFocus()throws Exception {
        BackgroundInspector.Runner old=BackgroundInspector.runner;
        try{
            BackgroundInspector.runner=(mode,apk)->new org.json.JSONObject("{\"state\":\"runtime_incomplete\",\"readyToFilter\":false}");
            DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            activity.getSharedPreferences("bridge",0).edit().putString("acknowledgedHookBuild",BridgeContract.BUILD).commit();
            BackgroundInspector.start(activity.getApplicationContext(),false,null);finishInspection(activity);
            assertEquals("runtime_incomplete",activity.getSharedPreferences("bridge",0).getString("state",""));
            assertTrue(activity.getSharedPreferences("status",0).getString("Background runtime","").contains(BridgeContract.BUILD));
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }finally{BackgroundInspector.runner=old;}
    }

    @Test public void dashboardInspectionNeverRequestsAReturnToCompanionWhenRuntimeIsIncomplete()throws Exception {
        BackgroundInspector.Runner old=BackgroundInspector.runner;
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        android.content.Context context=activity.getApplicationContext();
        final Intent[] received={null};
        android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context c,Intent i){received[0]=i;}
        };
        String action="dev.dov.internettools.TEST_DASHBOARD_RETURN";
        context.registerReceiver(receiver,new android.content.IntentFilter(action),android.content.Context.RECEIVER_NOT_EXPORTED);
        PendingIntent completion=PendingIntent.getBroadcast(context,901,new Intent(action).setPackage(context.getPackageName()),PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);
        try{
            BackgroundInspector.runner=(mode,apk)->new org.json.JSONObject("{\"state\":\"runtime_incomplete\",\"readyToFilter\":false}");
            activity.getSharedPreferences("bridge",0).edit().putString("acknowledgedHookBuild",BridgeContract.BUILD).commit();
            BackgroundInspector.start(context,true,completion);finishInspection(activity);
            assertNotNull(received[0]);assertTrue(received[0].getBooleanExtra("keepBrowser",false));
            assertEquals("runtime_incomplete",activity.getSharedPreferences("bridge",0).getString("state",""));
        }finally{completion.cancel();context.unregisterReceiver(receiver);BackgroundInspector.runner=old;}
    }

    @Test public void successfulStopRetiresOldProcessSnapshotsButPreservesRecordedRepair()throws Exception {
        BrowserControl.Stopper old=BrowserControl.stopper;
        try{
            BrowserControl.stopper=()->true;DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
            SharedPreferences status=activity.getSharedPreferences("status",0);
            status.edit().putString(LinkPolicy.BROWSER,"old build").putString(LinkPolicy.BROWSER+":privileged_process8","old renderer")
                .putString("uBO startup fix","recorded repair").commit();
            BrowserControl.stop(activity,false);finishRootWork(activity);
            assertFalse(status.contains(LinkPolicy.BROWSER));assertFalse(status.contains(LinkPolicy.BROWSER+":privileged_process8"));
            assertEquals("recorded repair",status.getString("uBO startup fix",""));
        }finally{BrowserControl.stopper=old;}
    }

    @Test public void renderMaterial3AmoledScreen() throws Exception {
        DiagnosticsActivity activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
        android.content.res.TypedArray attrs=activity.obtainStyledAttributes(new int[]{com.google.android.material.R.attr.isMaterial3Theme});
        assertTrue("Real Material 3 theme required",attrs.getBoolean(0,false)); attrs.recycle();
        View view=activity.getWindow().getDecorView();
        int width=1236,height=2676;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        File out=new File("build/ui-preview.png"); out.getParentFile().mkdirs();
        try(FileOutputStream stream=new FileOutputStream(out)){ image.compress(Bitmap.CompressFormat.PNG,100,stream); }
        assertEquals("AMOLED background must be pure black",0xff000000,image.getPixel(4,100));
    }
}
