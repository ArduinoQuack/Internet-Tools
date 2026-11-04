package dev.dov.internettools;

import android.content.Intent;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int TEXT=0xfff2f5f1,MUTED=0xffabb5ad,MINT=0xffb7f2cf,SURFACE=0xff141715;
    private SharedPreferences settings,bridge;
    private MaterialSwitch privacy,normalLinks;
    private MaterialButton ubo,restart;
    private TextView status,message;
    private LinearLayout restartRow;
    private boolean updating;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable deadline=()->{BridgeContract.expireUnacknowledged(bridge,System.currentTimeMillis());refresh();};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        settings=SettingsStore.prefs(this);bridge=getSharedPreferences("bridge",0);
        LinearLayout frame=column();frame.setBackgroundColor(Color.BLACK);
        ViewCompat.setOnApplyWindowInsetsListener(frame,(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(0,bars.top,0,bars.bottom);return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=column();body.setPadding(dp(24),dp(28),dp(24),dp(20));scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,-1));setContentView(frame);
        TextView title=text("Internet Tools",28,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",0));body.addView(title);space(body,5);
        body.addView(text("Samsung Internet",14,MUTED));space(body,28);

        LinearLayout block=card(body);block.addView(text("uBlock Origin",23,TEXT));space(block,6);
        block.addView(text("Blocks ads, trackers and page scripts.",14,MUTED));space(block,17);
        status=text("",14,MINT);block.addView(status);space(block,16);
        ubo=button("uBlock settings",false);ubo.setId(R.id.open_ubo);ubo.setOnClickListener(v->{if(!busy())BridgeContract.send(this,installed()?"runtime_open":"install");});block.addView(ubo,new LinearLayout.LayoutParams(-1,dp(54)));space(body,14);

        LinearLayout samsung=card(body);privacy=switchRow(samsung,"Samsung privacy","Limit analytics. Turn off browser sync and smart shopping.",R.id.privacy_switch);privacy.setOnCheckedChangeListener((v,on)->save(SettingsStore.PRIVACY,on));
        MaterialButton includes=button("What's included",true);includes.setId(R.id.privacy_details);includes.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle("Samsung privacy")
            .setMessage("• Samsung analytics\n• Browser and open-tab sync\n• Smart shopping and coupons\n• Browsing activity shared with other Samsung services\n\nApplies to Samsung Internet. This does not block every Samsung network connection.")
            .setPositiveButton("Done",null).show());samsung.addView(includes,new LinearLayout.LayoutParams(-1,dp(46)));space(body,14);

        LinearLayout links=card(body);normalLinks=switchRow(links,"Keep new links out of Secret mode","Open links from other apps in normal tabs, even if you last used Secret mode.",R.id.normal_links_switch);normalLinks.setOnCheckedChangeListener((v,on)->save(SettingsStore.NORMAL_LINKS,on));space(body,20);

        restartRow=column();message=text("Restart Samsung Internet to apply changes.",13,MUTED);restartRow.addView(message);space(restartRow,10);
        restart=button("Restart browser",true);restart.setId(R.id.restart_browser);restart.setOnClickListener(v->BrowserControl.restart(this));restartRow.addView(restart,new LinearLayout.LayoutParams(-1,dp(52)));body.addView(restartRow);space(body,20);
        MaterialButton support=button("Troubleshooting",true);support.setId(R.id.support_tools);support.setOnClickListener(v->startActivity(new Intent(this,DiagnosticsActivity.class)));body.addView(support,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView version=text("1.0.1",12,0xff738078);version.setGravity(Gravity.CENTER);body.addView(version);refresh();
    }
    private void save(String key,boolean value){if(updating)return;SettingsStore.set(this,key,value);refresh();}
    private boolean installed(){
        String state=bridge.getString("state","");if("removed".equals(state)||"not_installed".equals(state))return false;
        try{return new org.json.JSONObject(getSharedPreferences("status",0).getString("Native extension","{}")).optBoolean("registered",false);}catch(Exception e){return false;}
    }
    private boolean busy(){return OperationState.busy(bridge.getString("state",""));}
    private void refresh(){
        if(status==null)return;updating=true;privacy.setChecked(settings.getBoolean(SettingsStore.PRIVACY,true));normalLinks.setChecked(settings.getBoolean(SettingsStore.NORMAL_LINKS,true));updating=false;
        String state=bridge.getString("state","");boolean working=busy();boolean exists=installed();
        boolean enabled=false;try{enabled=new org.json.JSONObject(getSharedPreferences("status",0).getString("Native extension","{}")).optBoolean("enabled",false);}catch(Exception ignored){}
        status.setText(working?"Working…":exists?(enabled?"Enabled":"Off"):"Ready to set up");ubo.setText(exists?"uBlock settings":"Set up uBlock");ubo.setEnabled(!working);restart.setEnabled(!working);
        boolean newHook=!BridgeContract.BUILD.equals(bridge.getString("acknowledgedHookBuild",""));
        boolean pending=settings.getBoolean("restart_pending",false)||newHook;
        boolean error=state.contains("failed")||state.contains("timeout")||state.equals("no_response")||state.equals("hook_update_required");
        restartRow.setVisibility(pending||error?View.VISIBLE:View.GONE);
        message.setText(error?"Restart Samsung Internet. If it still won't connect, check that Internet Tools is enabled in LSPosed.":"Restart Samsung Internet to apply changes.");
        handler.removeCallbacks(deadline);
        if(working){long due=state.equals("connecting")?bridge.getLong("sent",System.currentTimeMillis())+BridgeContract.TIMEOUT_MS:bridge.getLong("deadline",System.currentTimeMillis()+15000);handler.postDelayed(deadline,Math.max(1,due-System.currentTimeMillis()+50));}
    }
    @Override protected void onResume(){super.onResume();settings.registerOnSharedPreferenceChangeListener(this);bridge.registerOnSharedPreferenceChangeListener(this);BridgeContract.expireUnacknowledged(bridge,System.currentTimeMillis());refresh();}
    @Override protected void onPause(){handler.removeCallbacks(deadline);settings.unregisterOnSharedPreferenceChangeListener(this);bridge.unregisterOnSharedPreferenceChangeListener(this);super.onPause();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){refresh();}
    private MaterialSwitch switchRow(LinearLayout parent,String title,String subtitle,int id){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout words=column();words.addView(text(title,18,TEXT));space(words,7);TextView hint=text(subtitle,13,MUTED);hint.setLineSpacing(dp(3),1);words.addView(hint);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(14);row.addView(words,lp);
        MaterialSwitch toggle=new MaterialSwitch(this);toggle.setId(id);toggle.setContentDescription(title);row.addView(toggle,new LinearLayout.LayoutParams(-2,-2));parent.addView(row);return toggle;
    }
    private LinearLayout card(LinearLayout body){MaterialCardView c=new MaterialCardView(this);c.setRadius(dp(26));c.setCardBackgroundColor(SURFACE);c.setStrokeWidth(0);c.setCardElevation(0);LinearLayout inner=column();inner.setPadding(dp(22),dp(22),dp(22),dp(18));c.addView(inner);body.addView(c,new LinearLayout.LayoutParams(-1,-2));return inner;}
    private MaterialButton button(String label,boolean quiet){MaterialButton b=new MaterialButton(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setCornerRadius(dp(26));if(quiet){b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));b.setTextColor(MINT);b.setElevation(0);}return b;}
    private TextView text(String value,int size,int color){TextView t=new com.google.android.material.textview.MaterialTextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void space(LinearLayout v,int size){v.addView(new View(this),new LinearLayout.LayoutParams(1,dp(size)));}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
