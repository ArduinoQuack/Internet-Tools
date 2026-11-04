package dev.dov.internettools;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class BackgroundInspector {
    interface Runner { JSONObject run(String mode,String apk) throws Exception; }
    static Runner runner=BackgroundInspector::root;
    private static String quote(String text){return "'"+text.replace("'","'\\''")+"'";}
    private static JSONObject root(String mode,String apk)throws Exception{
        if(!mode.equals("probe")&&!mode.equals("open"))throw new IllegalArgumentException("mode");
        String command="CLASSPATH="+quote(apk)+" /system/bin/app_process /system/bin dev.dov.internettools.BackgroundInspectorMain "+mode+" "+quote(apk);
        Process p=new ProcessBuilder("su","-c",command).start();
        try{
            if(!p.waitFor(65,TimeUnit.SECONDS))throw new IOException("background_inspector_timeout");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            try(InputStream in=p.getInputStream()){while((n=in.read(b))!=-1){if(out.size()+n>131072)throw new IOException("inspector_output_too_large");out.write(b,0,n);}}
            if(p.exitValue()!=0)throw new IOException("root_inspector_failed");
            return new JSONObject(out.toString("UTF-8").trim());
        }finally{p.destroy();}
    }
    static void start(Activity activity,boolean open){start(activity,open,null);}
    static void start(Context context,boolean open,PendingIntent completion){
        SharedPreferences prefs=context.getSharedPreferences("bridge",Context.MODE_PRIVATE);
        if(!BridgeContract.BUILD.equals(prefs.getString("acknowledgedHookBuild",""))){
            prefs.edit().putBoolean("backgroundInspectionPending",false).putBoolean("active",false).putString("state","hook_update_required")
                .putString("detail","No current-build browser acknowledgement. Use Restart & test.").commit();return;
        }
        String ticket=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        prefs.edit().putBoolean("backgroundInspectionPending",false).putString("request",ticket).putString("command",open?"inspect_open":"inspect")
            .putBoolean("active",true).putString("state","checking_background").putString("detail","Connecting to uBO's actual background runtime")
            .putLong("sent",now).putLong("deadline",now+70000).commit();
        new Thread(()->{
            JSONObject result;
            try{result=runner.run(open?"open":"probe",context.getApplicationInfo().sourceDir);}
            catch(Exception e){result=new JSONObject();try{result.put("state","background_inspection_failed").put("error",e.getClass().getSimpleName()+(e instanceof IOException?": "+String.valueOf(e.getMessage()).substring(0,Math.min(180,String.valueOf(e.getMessage()).length())):""));}catch(Exception ignored){}}
            DiagnosticSession.finish();
            final JSONObject report=result;
            new Handler(Looper.getMainLooper()).post(()->{
                if(!ticket.equals(prefs.getString("request",""))||!prefs.getBoolean("active",false))return;
                try{report.put("acknowledgedHookBuild",prefs.getString("acknowledgedHookBuild",""));}catch(Exception ignored){}
                String state=report.optString("state","background_inspection_failed");String text=report.toString();
                if(state.equals("startup_restart_required")&&prefs.getBoolean("rootTestChain",false)&&!prefs.getBoolean("startupRepairRestartUsed",false))
                    prefs.edit().putBoolean("startupRepairRestartPending",true).commit();
                prefs.edit().putBoolean("active",false).putString("state",state).putString("detail",text).putLong("updated",System.currentTimeMillis()).commit();
                context.getSharedPreferences("status",0).edit().putString("uBlock Origin",text).putString("Background runtime",text).apply();
                if(completion!=null){
                    try{completion.send(context,0,new Intent().putExtra("keepBrowser",open).putExtra("extensionVersion",report.optString("version","")));}catch(PendingIntent.CanceledException ignored){}
                }else if(context instanceof Activity&&open&&!((Activity)context).isFinishing()&&((Activity)context).hasWindowFocus()){
                    Activity activity=(Activity)context;
                    Intent launch=activity.getPackageManager().getLaunchIntentForPackage(LinkPolicy.BROWSER);if(launch!=null)activity.startActivity(launch);
                }
            });
        },"InternetTools-background-inspector").start();
    }
}
