package dev.dov.internettools;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.SharedPreferences;
import java.util.UUID;

final class BridgeContract {
    static final String BUILD = "1.0.1";
    static final String ACTION = "dev.dov.internettools.COMMAND";
    static final String REQUEST = "ip.request";
    static final String COMMAND = "ip.command";
    static final String RETURN="ip.return";
    static final String DIAGNOSTICS="ip.diagnostics";
    static final String REPLY = "ip.reply";
    static final long TIMEOUT_MS = 12000;

    static boolean validCommand(String action) {
        return "install".equals(action) || "open".equals(action) || "verify".equals(action) || "remove".equals(action) || "undo_fix".equals(action) || "runtime".equals(action) || "runtime_open".equals(action);
    }
    static boolean validEnvelope(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return false;
        PendingIntent callback = intent.getParcelableExtra(REPLY);
        String id = intent.getStringExtra(REQUEST);
        return callback != null && "dev.dov.internettools".equals(callback.getCreatorPackage())
            && validCommand(intent.getStringExtra(COMMAND)) && id != null && !id.isEmpty() && id.length() <= 80;
    }

    static void send(Activity activity, String command) { send(activity,command,false); }
    static void send(Activity activity, String command, boolean rootChain) {
        if (!validCommand(command)) throw new IllegalArgumentException("Unknown action");
        SharedPreferences chainPrefs=activity.getSharedPreferences("bridge",Context.MODE_PRIVATE);
        if (!rootChain) chainPrefs.edit().putBoolean("rootTestChain",false).putBoolean("startupRepairRestartUsed",false).putBoolean("startupRepairRestartPending",false).commit();
        String id = UUID.randomUUID().toString();
        SharedPreferences bridge = activity.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        bridge.edit().putString("request", id).putString("command", command)
            .putString("state", "connecting").putString("detail", "Waiting for Samsung Internet")
            .putLong("sent", System.currentTimeMillis()).putLong("deadline", System.currentTimeMillis() + OperationState.budget(command))
            .putString("acknowledgedHookBuild","").putBoolean("diagnosticRequest",activity instanceof DiagnosticsActivity).putBoolean("active", true).putBoolean("backgroundInspectionPending",false).putLong("ack", 0).commit();
        Intent response = new Intent(activity, BridgeReceiver.class).setAction("dev.dov.internettools.REPLY." + id).putExtra(REQUEST, id);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent reply = PendingIntent.getBroadcast(activity, 30, response, flags);
        Intent returnIntent=new Intent(activity,activity instanceof DiagnosticsActivity?DiagnosticsActivity.class:MainActivity.class)
            .setAction("dev.dov.internettools.RETURN."+id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent returnToApp=PendingIntent.getActivity(activity,31,returnIntent,PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent launch = new Intent(ACTION).setClassName(LinkPolicy.BROWSER, "com.sec.android.app.sbrowser.SBrowserMainActivity")
            .putExtra(REQUEST, id).putExtra(COMMAND, command).putExtra(REPLY, reply).putExtra(DIAGNOSTICS,activity instanceof DiagnosticsActivity).putExtra(RETURN,returnToApp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { activity.startActivity(launch); }
        catch (RuntimeException e) {
            bridge.edit().putBoolean("active", false).putString("state", "launch_failed").putString("detail", "Samsung Internet could not be opened").commit();
            reply.cancel();
        }
    }

    static void expireUnacknowledged(SharedPreferences bridge, long now) {
        String state = bridge.getString("state", "");
        if (!OperationState.busy(state)) return;
        boolean noAck = "connecting".equals(state) && bridge.getLong("ack", 0) == 0
            && now - bridge.getLong("sent", now) >= TIMEOUT_MS;
        long deadline = bridge.getLong("deadline", bridge.getLong("sent", now) + OperationState.budget(bridge.getString("command", "verify")));
        if (noAck || now >= deadline) {
            bridge.edit().putBoolean("active", false).putString("state", noAck ? "no_response" : "operation_timeout")
                .putString("detail", noAck ? "Browser hook did not respond. Use Restart & test." : "The action reached its time limit. Use Restart & test; the previous request is closed.").commit();
        }
    }
}
