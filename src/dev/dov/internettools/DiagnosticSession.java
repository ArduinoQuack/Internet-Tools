package dev.dov.internettools;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.concurrent.*;

/** One explicit bounded recording; stays local until the user copies the report. */
final class DiagnosticSession {
    private static volatile Process running;
    private static boolean starting;
    private static volatile boolean finishing;
    static volatile boolean disabledForTests;
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    static synchronized void start(Context context){start(context,null);}
    static synchronized void start(Context context,Runnable ready){
        if(disabledForTests){if(ready!=null)ready.run();return;}
        final java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable signal=()->{if(ready!=null&&once.compareAndSet(false,true))new android.os.Handler(android.os.Looper.getMainLooper()).post(ready);};
        Context app=context.getApplicationContext();
        if(running!=null&&finishing){new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->start(app,ready),500);return;}
        if(running!=null||starting){signal.run();return;}
        starting=true;
        SharedPreferences prefs=app.getSharedPreferences("status",0);
        String previous=prefs.getString("Session log","");
        if(previous.startsWith("{"))prefs.edit().putString("Previous session log",previous).apply();
        prefs.edit().putString("Session log","Recording browser and renderer lifecycle (maximum 90 seconds)…").apply();
        new Thread(()->{
            Process process=null;boolean signaled=false;
            try{
                String command="CLASSPATH="+quote(app.getApplicationInfo().sourceDir)+" /system/bin/app_process /system/bin dev.dov.internettools.DiagnosticCollectorMain";
                process=new ProcessBuilder("su","-c",command).start();
                synchronized(DiagnosticSession.class){if(running!=null){process.destroy();signal.run();return;}running=process;starting=false;finishing=false;}
                final Process child=process;
                Thread deadline=new Thread(()->{try{Thread.sleep(105000);child.destroy();}catch(Exception ignored){}},"InternetTools-log-deadline");deadline.setDaemon(true);deadline.start();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){
                    String line;while((line=reader.readLine())!=null){
                        if(line.equals("READY")&&!signaled){signaled=true;signal.run();}
                        else if(line.startsWith("{")&&line.length()<180000)prefs.edit().putString("Session log",line).apply();
                    }
                }
                if(process.waitFor(2,TimeUnit.SECONDS)&&process.exitValue()!=0)throw new IOException("collector_exit");
            }catch(Exception e){prefs.edit().putString("Session log","Capture failed: "+e.getClass().getSimpleName()+". Allow Internet Tools in KernelSU.").apply();}
            finally{if(process!=null)process.destroy();synchronized(DiagnosticSession.class){if(running==process)running=null;starting=false;finishing=false;}if(!signaled)signal.run();}
        },"InternetTools-session-capture").start();
        // Do not hold the UI behind a root authorization prompt.
        if(ready!=null)new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(signal,5000);
    }
    static void finish(){Process p=running;if(p!=null)try{finishing=true;p.getOutputStream().write(1);p.getOutputStream().flush();}catch(IOException ignored){}}
}
