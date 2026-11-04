package dev.dov.internettools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** A non-exported receiver reached only through this app's explicit PendingIntent capability. */
public final class BridgeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        SharedPreferences prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        String id = intent.getStringExtra(BridgeContract.REQUEST);
        if (id == null || !id.equals(prefs.getString("request", ""))) return;
        if (intent.getBooleanExtra("metadataOnly", false)) {
            String console = intent.getStringExtra("uboConsole");
            if (console != null) context.getSharedPreferences("status", Context.MODE_PRIVATE).edit().putString("uBO console", console).apply();
            return;
        }
        BridgeContract.expireUnacknowledged(prefs, System.currentTimeMillis());
        if (!prefs.getBoolean("active", false)) return;
        String loadedBuild=intent.getStringExtra("hookBuild");
        String handshake="Expected: "+BridgeContract.BUILD+"\nAcknowledged: "+(loadedBuild==null?"unknown":loadedBuild)+"\nBrowser PID: "+intent.getIntExtra("browserPid",-1);
        context.getSharedPreferences("status",Context.MODE_PRIVATE).edit().putString("Current hook handshake",handshake).apply();
        if (!BridgeContract.BUILD.equals(loadedBuild)) {
            prefs.edit().putBoolean("active",false).putBoolean("backgroundInspectionPending",false).putString("acknowledgedHookBuild","").putString("state","hook_update_required")
                .putString("detail","Loaded hook: "+loadedBuild+"; expected: "+BridgeContract.BUILD+". Use Restart & test. If it still reports the old build, reload Internet Tools in LSPosed.").commit(); return;
        }
        prefs.edit().putString("acknowledgedHookBuild",loadedBuild).commit();
        String repair = intent.getStringExtra("managedRepair");
        if (repair != null) context.getSharedPreferences("status",Context.MODE_PRIVATE).edit().putString("uBO startup fix",repair).apply();
        String state = intent.getStringExtra("state");
        String detail = intent.getStringExtra("detail");
        if("runtime_inspection_ready".equals(state)&&!prefs.getBoolean("diagnosticRequest",false)){
            prefs.edit().putBoolean("active",false).putBoolean("backgroundInspectionPending",false).putString("state","unexpected_diagnostic_request").putString("detail","Use Troubleshooting to run a check.").commit();return;
        }
        if ("runtime_inspection_ready".equals(state)) prefs.edit().putBoolean("backgroundInspectionPending",true)
            .putBoolean("backgroundInspectionOpen","runtime_open".equals(prefs.getString("command",""))).commit();
        if ("runtime_incomplete".equals(state) && repair != null && prefs.getBoolean("rootTestChain",false)
            && !prefs.getBoolean("startupRepairRestartUsed",false)) {
            try {
                org.json.JSONObject fix=new org.json.JSONObject(repair);
                if ("managed_cache_initialized".equals(fix.optString("state")) && fix.optBoolean("changed"))
                    prefs.edit().putBoolean("startupRepairRestartPending",true).commit();
            } catch (org.json.JSONException ignored) { }
        }
        if (state == null) return;
        if("extension_enabled".equals(state))SettingsStore.prefs(context).edit().putBoolean("restart_pending",false).apply();
        if(!OperationState.busy(state)&&!"runtime_inspection_ready".equals(state))DiagnosticSession.finish();
        long now = System.currentTimeMillis();
        prefs.edit().putBoolean("active", OperationState.busy(state)).putLong("ack", now).putString("state", state)
            .putString("detail", detail == null ? "" : detail).putLong("updated", now).commit();
        String nativeState = intent.getStringExtra("nativeState");
        if (nativeState != null && !nativeState.isEmpty()) context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
            .putString("Native extension", nativeState).apply();
        String console = intent.getStringExtra("uboConsole");
        if (console != null) context.getSharedPreferences("status", Context.MODE_PRIVATE).edit().putString("uBO console", console).apply();
        String startupState = intent.getStringExtra("startupState");
        if ("received".equals(state)) context.getSharedPreferences("status", Context.MODE_PRIVATE).edit().remove("Startup before native reload").apply();
        if (startupState != null && !startupState.isEmpty()) context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
            .putString("Startup before native reload", startupState).apply();
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
            .putString("uBlock Origin", state + "\n" + (detail == null ? "" : detail) + "\nReported: " + new java.util.Date(now)).apply();
        if("runtime_inspection_ready".equals(state)){
            android.app.PendingIntent completion=intent.getParcelableExtra("inspectionReturn");
            if(completion!=null&&LinkPolicy.BROWSER.equals(completion.getCreatorPackage()))
                BackgroundInspector.start(context,"runtime_open".equals(prefs.getString("command","")),completion);
        }

    }
}
