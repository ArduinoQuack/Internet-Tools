package dev.dov.internettools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textview.MaterialTextView;
import java.util.Map;

public final class DiagnosticsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int MINT = 0xffb7f2cf, TEXT = 0xfff2f5f1, MUTED = 0xffabb5ad, SURFACE = 0xff121413;
    private SharedPreferences bridge;
    private TextView status, detail;
    private Chip connection;
    private MaterialButton install, open, verify, remove, stopBrowser, restartTest;
    private TextView diagnosticLog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            BridgeContract.expireUnacknowledged(bridge, System.currentTimeMillis());
            updateState(); handler.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        bridge = getSharedPreferences("bridge", MODE_PRIVATE);
        LinearLayout frame = column(); frame.setBackgroundColor(Color.BLACK);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = column(); body.setPadding(dp(24), dp(24), dp(24), dp(20));
        scroll.addView(body); frame.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); setContentView(frame);

        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Troubleshooting", 22, TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        connection = new Chip(this); connection.setTextSize(11); connection.setCheckable(false);
        connection.setChipBackgroundColor(ColorStateList.valueOf(0xff1b241e)); connection.setTextColor(MINT);
        connection.setChipStrokeWidth(0); connection.setEnsureMinTouchTargetSize(false);
        connection.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle("Browser connection")
            .setMessage("Enable Internet Tools in LSPosed for Samsung Internet, then force stop the browser once after updating.")
            .setPositiveButton("Got it", null).show());
        header.addView(connection); body.addView(header); space(body, 32);

        MaterialCardView uboCard = card(); LinearLayout ubo = column(); ubo.setPadding(dp(22),dp(22),dp(22),dp(12));
        LinearLayout labelRow = row(); labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("uBlock Origin", 23, TEXT); name.setTypeface(Typeface.create("sans-serif-medium",0));
        labelRow.addView(name, new LinearLayout.LayoutParams(0,-2,1));
        TextView badge = text("uBO", 12, MINT); badge.setTypeface(Typeface.DEFAULT_BOLD); labelRow.addView(badge);
        ubo.addView(labelRow); space(ubo, 14);
        status = text("Ready to install", 16, MINT); ubo.addView(status); space(ubo,5);
        detail = text("Samsung compatibility build · 1.75.0", 13, MUTED); detail.setLineSpacing(dp(2),1); ubo.addView(detail);
        space(ubo,20);
        install = button("Install uBO", true); install.setId(R.id.install_ubo); install.setOnClickListener(v -> run("install"));
        ubo.addView(install, new LinearLayout.LayoutParams(-1,dp(56))); space(ubo,8);
        LinearLayout actions = row();
        open = button("Dashboard", true); open.setId(R.id.open_ubo); open.setOnClickListener(v -> run("open"));
        verify = button("Verify", true); verify.setId(R.id.verify_ubo); verify.setOnClickListener(v -> run("verify"));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,dp(56),1); left.rightMargin=dp(5);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,dp(56),1); right.leftMargin=dp(5);
        actions.addView(open,left); actions.addView(verify,right); ubo.addView(actions);
        remove = textButton("Remove uBO"); remove.setId(R.id.remove_ubo); remove.setTextColor(0xffffb4ab);
        remove.setOnClickListener(v -> run("remove")); ubo.addView(remove,new LinearLayout.LayoutParams(-1,dp(48)));
        uboCard.addView(ubo); body.addView(uboCard); space(body,12);
        restartTest = button("Restart & test", false); restartTest.setId(R.id.restart_test);
        restartTest.setOnClickListener(v -> BrowserControl.stop(this, true));
        body.addView(restartTest, new LinearLayout.LayoutParams(-1,dp(56)));
        stopBrowser = button("Force stop Samsung Internet", true); stopBrowser.setId(R.id.force_stop);
        stopBrowser.setOnClickListener(v -> BrowserControl.stop(this, false));
        body.addView(stopBrowser, new LinearLayout.LayoutParams(-1,dp(56))); space(body,16);

        LinearLayout footer=row();
        MaterialButton diagnostics=textButton("View report"); diagnostics.setId(R.id.diagnostics); diagnostics.setOnClickListener(v -> showDiagnostics());
        MaterialButton updates=textButton("Updates"); updates.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
            .setTitle("uBO updates").setMessage("Filter lists auto-update while uBO runs. Compatibility builds update with Internet Tools. Based on uBlock Origin 1.75.0.")
            .setPositiveButton("Done",null).show());
        footer.addView(diagnostics,new LinearLayout.LayoutParams(0,dp(48),1)); footer.addView(updates,new LinearLayout.LayoutParams(0,dp(48),1)); body.addView(footer);
        space(body,12); TextView version=text("1.0.1",12,0xff738078); version.setGravity(Gravity.CENTER); body.addView(version);
        MaterialButton back=textButton("Back");back.setOnClickListener(v->finish());body.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));
        updateState();
    }
    @Override protected void onResume() { super.onResume(); bridge.registerOnSharedPreferenceChangeListener(this); handler.post(refresh);
        handler.post(this::finishPendingStartupRestart);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); bridge.unregisterOnSharedPreferenceChangeListener(this); super.onPause(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key) { handler.post(()->{updateState();finishPendingStartupRestart();}); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if(focused)finishPendingStartupRestart(); }
    private void finishPendingStartupRestart() {
        if(bridge!=null&&hasWindowFocus()&&"runtime_inspection_ready".equals(bridge.getString("state",""))&&bridge.getBoolean("backgroundInspectionPending",false)) {
            BackgroundInspector.start(this,bridge.getBoolean("backgroundInspectionOpen",false));return;
        }
        if(bridge!=null&&hasWindowFocus()&&bridge.getBoolean("startupRepairRestartPending",false)&&!bridge.getBoolean("startupRepairRestartUsed",false)) BrowserControl.finishStartupRestart(this);
    }
    private void run(String action) {
        if(OperationState.busy(bridge.getString("state",""))) return;
        DiagnosticSession.start(this);
        BridgeContract.send(this, action.equals("verify")?"runtime":action.equals("open")?"runtime_open":action); updateState();
    }
    private void updateState() {
        if(status==null)return;
        String state=bridge.getString("state", "");
        boolean connected=bridge.getLong("ack",0)>0;
        connection.setText(connected?"Connected":"Not connected");
        boolean busy=OperationState.busy(state);
        stopBrowser.setEnabled(!state.equals("force_stopping")); restartTest.setEnabled(!busy);
        if(diagnosticLog != null) diagnosticLog.setText(report());
        install.setEnabled(!busy); open.setEnabled(!busy); verify.setEnabled(!busy); remove.setEnabled(!busy);
        if(state.isEmpty()) { status.setText("Ready to install"); detail.setText("Samsung compatibility build · 1.75.0"); return; }
        String label;
        switch(state) {
            case "startup_restart_required": label="Startup cache fixed; restart needed"; break;
            case "checking_background": label="Checking uBO background…"; break;
            case "runtime_initializing": label="uBO is still initializing"; break;
            case "background_core_missing": label="uBO startup did not create its core"; break;
            case "background_bindings_unavailable": label="Extension APIs are unavailable"; break;
            case "background_probe_failed": case "filter_engine_inspection_failed": label="uBO inspection failed"; break;
            case "background_inspection_failed": label="Background connection failed"; break;
            case "background_target_missing": label="uBO background was not exposed"; break;
            case "repairing_startup": label="Repairing uBO startup…"; break;
            case "startup_fix_removed": label="Startup fix removed"; break;
            case "startup_fix_not_owned": label="No startup fix to undo"; break;
            case "startup_fix_cache_changed": label="Current startup cache preserved"; break;
            case "hook_update_required": label="Browser hook needs a restart"; break;
            case "force_stopping": label="Stopping Samsung Internet…"; break;
            case "browser_stopped": label="Samsung Internet stopped"; break;
            case "force_stop_failed": label="Force stop failed"; break;
            case "operation_timeout": label="Check timed out"; break;
            case "javascript_callback_timeout": label="Browser script did not respond"; break;
            case "connecting": label="Connecting…"; break;
            case "received": case "running": label="Working…"; break;
            case "installed_not_verified": label="Starting uBO…"; break;
            case "copying_verified_crx": case "install_requested": label="Installing…"; break;
            case "opening_dashboard": label="Checking uBO…"; break;
            case "starting_extension_service": label="Starting extension service…"; break;
            case "restarting_extension": label="Restarting uBO…"; break;
            case "opening_extension": label="Opening uBO…"; break;
            case "extension_opened": label="uBO view opened"; break;
            case "extension_disabled": label="uBO is disabled"; break;
            case "blocking_verified": label="Blocking verified"; break;
            case "self_test_failed": label="Live filtering test failed"; break;
            case "runtime_ready": label="uBO runtime ready"; break;
            case "dashboard_opened": label="Dashboard opened"; break;
            case "removed": case "not_installed": label="Not installed"; break;
            case "extension_page_blocked": label="Samsung blocked the uBO page"; break;
            case "probe_context_lost": case "extension_context_lost": label="uBO page was replaced"; break;
            case "probe_origin_changed": label="uBO page navigated away"; break;
            case "runtime_incomplete": label="Filtering is not running"; break;
            case "no_response": label="Browser did not respond"; break;
            case "install_rejected": label="Installation rejected"; break;
            default: label=state.contains("fail")||state.contains("unavailable")||state.contains("timeout")||state.contains("not_started")?"Action failed":"Needs attention";
        }
        status.setText(label);
        boolean failed=!busy&&!state.isEmpty()&&!state.equals("blocking_verified")&&!state.equals("runtime_ready")&&!state.equals("dashboard_opened")&&!state.equals("browser_stopped")&&!state.equals("removed")&&!state.equals("not_installed")&&!state.equals("startup_fix_removed")&&!state.equals("startup_fix_not_owned")&&!state.equals("startup_fix_cache_changed");
        status.setTextColor(failed?0xffffb4ab:MINT);
        if(state.equals("blocking_verified")) detail.setText("Network · Cosmetic · Scriptlets verified");
        else if(state.equals("runtime_ready")) detail.setText("Filters loaded · Live site testing still needed");
        else if(state.equals("no_response")) detail.setText("Force stop Samsung Internet, then retry.");
        else if(busy) detail.setText(Math.max(0,(bridge.getLong("deadline",System.currentTimeMillis())-System.currentTimeMillis()+999)/1000)+" seconds remaining");
        else if(state.equals("force_stop_failed")||state.equals("browser_stopped")||state.equals("operation_timeout")||state.equals("hook_update_required")) detail.setText(bridge.getString("detail",""));
        else if(state.equals("dashboard_opened")||state.equals("extension_opened")) detail.setText("Samsung’s native uBO view");
        else if(state.equals("startup_fix_removed")||state.equals("startup_fix_not_owned")||state.equals("startup_fix_cache_changed")) detail.setText("Your settings and filters are unchanged");
        else if(state.equals("removed")||state.equals("not_installed")) detail.setText("Samsung compatibility build · 1.75.0");
        else detail.setText("Open Diagnostics for details");
    }
    private void showDiagnostics() {
        BottomSheetDialog sheet=new BottomSheetDialog(this);
        LinearLayout content=column(); content.setPadding(dp(24),dp(24),dp(24),dp(24)); content.setBackgroundColor(SURFACE);
        content.addView(text("Diagnostics",24,TEXT)); space(content,16);
        ScrollView scroll=new ScrollView(this); TextView log=text(report(),12,MUTED); diagnosticLog=log;
        log.setTypeface(Typeface.MONOSPACE); log.setTextIsSelectable(true); scroll.addView(log);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(260))); space(content,16);
        MaterialButton copy=button("Copy report",false); copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Internet Tools diagnostics",report()));
            com.google.android.material.snackbar.Snackbar.make(content,"Report copied",1200).show();
        }); content.addView(copy,new LinearLayout.LayoutParams(-1,dp(56))); sheet.setOnDismissListener(v -> diagnosticLog=null); sheet.setContentView(content); sheet.show();
    }
    private String report() {
        StringBuilder result=new StringBuilder("Internet Tools 1.0.1\n\n");
        result.append("Device: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");
        result.append(bridge.getString("state","No action yet")).append('\n').append(bridge.getString("detail",""));
        for(Map.Entry<String,?> e:getSharedPreferences("status",MODE_PRIVATE).getAll().entrySet()) result.append("\n\n").append(e.getKey().equals("uBO startup fix")?"uBO startup fix (previously recorded)":e.getKey()).append('\n').append(e.getValue());
        return result.toString();
    }
    private void settingsRow(LinearLayout parent,String a,String b) { LinearLayout row=row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(17),0,dp(17)); row.addView(text(a,14,TEXT),new LinearLayout.LayoutParams(0,-2,1)); row.addView(text(b,12,MUTED)); parent.addView(row); }
    private MaterialCardView card() { MaterialCardView c=new MaterialCardView(this); c.setRadius(dp(28)); c.setCardBackgroundColor(SURFACE); c.setStrokeWidth(0); c.setCardElevation(0); return c; }
    private MaterialButton button(String title,boolean outlined) { MaterialButton b=new MaterialButton(this); b.setText(title); b.setTextSize(14); b.setAllCaps(false); b.setCornerRadius(dp(28)); b.setInsetTop(dp(3)); b.setInsetBottom(dp(3)); if(outlined){ b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); b.setTextColor(TEXT); b.setStrokeColor(ColorStateList.valueOf(0xff58645c)); b.setStrokeWidth(dp(1)); } return b; }
    private MaterialButton textButton(String title) { MaterialButton b=button(title,false); b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); b.setTextColor(MINT); b.setElevation(0); return b; }
    private TextView text(String value,int size,int color) { MaterialTextView t=new MaterialTextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private void space(LinearLayout l,int height){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
