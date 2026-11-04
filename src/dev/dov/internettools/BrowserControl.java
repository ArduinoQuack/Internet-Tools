package dev.dov.internettools;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Fixed, user-requested root action; never runs caller-supplied shell commands. */
final class BrowserControl {
    interface Stopper { boolean stop() throws Exception; }
    static Stopper stopper = BrowserControl::stopWithRoot;
    private static boolean stopWithRoot() throws Exception {
        Process process = new ProcessBuilder("su", "-c", "am force-stop --user current com.sec.android.app.sbrowser").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) throw new IllegalStateException("Root request timed out");
            return process.exitValue() == 0;
        } finally { process.destroy(); }
    }
    static void restart(Activity activity){stopInternal(activity,true,false);}
    static void stop(Activity activity, boolean testAfter) { stop(activity,testAfter,false); }
    static void finishStartupRestart(Activity activity) { stop(activity,true,true); }
    private static void stop(Activity activity, boolean testAfter, boolean repairRetry) {
        if(testAfter) DiagnosticSession.start(activity,()->stopInternal(activity,testAfter,repairRetry));
        else stopInternal(activity,testAfter,repairRetry);
    }
    private static void stopInternal(Activity activity, boolean testAfter, boolean repairRetry) {
        SharedPreferences prefs = activity.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("backgroundInspectionPending",false).putBoolean("rootTestChain",testAfter).putBoolean("startupRepairRestartPending",false)
            .putBoolean("startupRepairRestartUsed",repairRetry).commit();
        String ticket = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        prefs.edit().putString("request", ticket).putString("command", testAfter ? "restart_test" : "stop")
            .putString("state", "force_stopping").putString("detail", "Waiting for root permission")
            .putLong("sent", now).putLong("deadline", now + 15000).putLong("ack", 0).putBoolean("active", true).commit();
        new Thread(() -> {
            String error = null;
            try { if (!stopper.stop()) error = "Root permission was denied or force stop failed. Allow Internet Tools in KernelSU."; }
            catch (Exception e) { error = "Force stop failed: " + e.getClass().getSimpleName() + ". Allow Internet Tools in KernelSU, then retry."; }
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!ticket.equals(prefs.getString("request", "")) || !prefs.getBoolean("active", false)) return;
                prefs.edit().putBoolean("active", false).putString("state", failure == null ? "browser_stopped" : "force_stop_failed")
                    .putString("detail", failure == null ? "Samsung Internet stopped" : failure).commit();
                if(failure==null){
                    // Retire previous process snapshots only after a successful stop.
                    SharedPreferences status=activity.getSharedPreferences("status",Context.MODE_PRIVATE);
                    SharedPreferences.Editor edit=status.edit();
                    for(String key:status.getAll().keySet())if(key.equals(LinkPolicy.BROWSER)||key.startsWith(LinkPolicy.BROWSER+":"))edit.remove(key);
                    edit.remove("Current hook handshake").remove("Native extension").remove("uBO console").remove("Background runtime").apply();
                }
                if (failure == null && testAfter && !activity.isFinishing()) BridgeContract.send(activity, "install", true);
            });
        }, "InternetTools-stop-browser").start();
    }
}
