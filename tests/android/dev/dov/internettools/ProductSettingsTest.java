package dev.dov.internettools;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProductSettingsTest {
    @org.junit.Before public void isolate(){DiagnosticSession.disabledForTests=true;}
    @Test public void settingsDefaultToPreviousBehaviorAndSwitchIndependently(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        MaterialSwitch privacy=a.findViewById(R.id.privacy_switch),links=a.findViewById(R.id.normal_links_switch);
        assertTrue(privacy.isChecked());assertTrue(links.isChecked());privacy.performClick();
        assertFalse(SettingsStore.snapshot(a).getBoolean(SettingsStore.PRIVACY));assertTrue(SettingsStore.snapshot(a).getBoolean(SettingsStore.NORMAL_LINKS));
        links.performClick();assertFalse(SettingsStore.snapshot(a).getBoolean(SettingsStore.NORMAL_LINKS));privacy.performClick();
        assertTrue(SettingsStore.snapshot(a).getBoolean(SettingsStore.PRIVACY));assertFalse(SettingsStore.snapshot(a).getBoolean(SettingsStore.NORMAL_LINKS));
        assertTrue(SettingsStore.prefs(a).getBoolean("restart_pending",false));assertNull(Shadows.shadowOf(a).getNextStartedActivity());
    }
    @Test public void mainScreenContainsNoTestsAndSupportIsSeparate(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNull(a.findViewById(R.id.verify_ubo));assertNull(a.findViewById(R.id.restart_test));assertNull(a.findViewById(R.id.remove_ubo));assertNull(a.findViewById(R.id.force_stop));
        a.findViewById(R.id.support_tools).performClick();Intent i=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(DiagnosticsActivity.class.getName(),i.getComponent().getClassName());
    }
    @Test public void ordinarySetupCannotOptIntoDiagnosticCapture(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();BridgeContract.send(a,"install");
        Intent i=Shadows.shadowOf(a).getNextStartedActivity();assertFalse(i.getBooleanExtra(BridgeContract.DIAGNOSTICS,true));assertFalse(a.getSharedPreferences("bridge",0).getBoolean("diagnosticRequest",true));
        assertNotNull(i.getParcelableExtra(BridgeContract.RETURN));assertTrue(BridgeContract.validEnvelope(i));assertFalse(a.getSharedPreferences("status",0).contains("Session log"));
    }
    @Test public void diagnosticControlsOptInExplicitly(){
        DiagnosticsActivity a=Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();BridgeContract.send(a,"runtime");
        assertTrue(Shadows.shadowOf(a).getNextStartedActivity().getBooleanExtra(BridgeContract.DIAGNOSTICS,false));
        assertTrue(a.getSharedPreferences("bridge",0).getBoolean("diagnosticRequest",false));
    }
    @Test public void unexpectedInspectionReplyCannotStartAProductionTest(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();BridgeContract.send(a,"install");
        String id=a.getSharedPreferences("bridge",0).getString("request","");
        new BridgeReceiver().onReceive(a,new Intent().putExtra(BridgeContract.REQUEST,id).putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","runtime_inspection_ready"));
        assertEquals("unexpected_diagnostic_request",a.getSharedPreferences("bridge",0).getString("state",""));assertFalse(a.getSharedPreferences("bridge",0).getBoolean("backgroundInspectionPending",true));
    }
    @Test public void browserOnlyProviderDoesNotPublishSettingsToOtherApps(){
        StatusProvider provider=Robolectric.buildContentProvider(StatusProvider.class).create().get();
        try{provider.call("settings",null,null);fail("App without browser UID must not read provider settings");}catch(SecurityException expected){}
    }
    @Test public void successfulSetupClearsRestartNoticeWithoutRootInspection(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();SettingsStore.set(a,SettingsStore.PRIVACY,false);BridgeContract.send(a,"install");
        String id=a.getSharedPreferences("bridge",0).getString("request","");
        new BridgeReceiver().onReceive(a,new Intent().putExtra(BridgeContract.REQUEST,id).putExtra("hookBuild",BridgeContract.BUILD).putExtra("state","extension_enabled").putExtra("nativeState","{\"registered\":true,\"enabled\":true}"));
        assertFalse(SettingsStore.prefs(a).getBoolean("restart_pending",true));assertEquals("extension_enabled",a.getSharedPreferences("bridge",0).getString("state",""));
        assertFalse(a.getSharedPreferences("bridge",0).getBoolean("backgroundInspectionPending",true));
    }
    @Test public void renderProductScreen()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        a.getSharedPreferences("status",0).edit().putString("Native extension","{\"registered\":true,\"enabled\":true}").commit();
        a.getSharedPreferences("bridge",0).edit().putString("acknowledgedHookBuild",BridgeContract.BUILD).commit();
        View v=a.getWindow().getDecorView();int w=1236,h=2676;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));
        File f=new File("build/product-preview.png");f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        assertEquals(0xff000000,bitmap.getPixel(4,100));
    }
}
