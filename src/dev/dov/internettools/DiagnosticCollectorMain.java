package dev.dov.internettools;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import org.json.*;

/** Ephemeral root log reader. Filters in memory, never writes an unfiltered logcat. */
public final class DiagnosticCollectorMain {
    private static final String BROWSER="com.sec.android.app.sbrowser";
    private static final Set<Integer> owned=Collections.synchronizedSet(new HashSet<>());
    private static final List<String> lines=Collections.synchronizedList(new ArrayList<>());
    private static final List<String> lifecycle=Collections.synchronizedList(new ArrayList<>());
    private static final Pattern ROW=Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+([^:]+):\\s?(.*)$");
    private static final Set<String> TAGS=new HashSet<>(Arrays.asList("InternetToolsTrace","TinAndroidExtensionView","TerraceExtensionsLoader","TerraceExtensionsManager","TerraceExtensionUtil","chromium","cr_ChildProcessConn","cr_ChildProcessLauncher","libc","DEBUG","AndroidRuntime"));
    private static void add(List<String> target,String value,int max){synchronized(target){if(target.size()>=max)target.remove(0);target.add(value);}}
    static String redact(String value){
        String s=value.replaceAll("(?i)(?:https?|wss?|file|content)://[^\\s\\\"'<>)]*","<url>")
            .replaceAll("(?i)(authorization|cookie|token|password|secret|bearer|account)[=: ]+[^\\s,;]+","$1=<redacted>")
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}","<email>")
            .replaceAll("[A-Za-z0-9_+/=-]{64,}","<long-value>");
        return s.substring(0,Math.min(700,s.length()));
    }
    private static String processName(int pid){try(FileInputStream in=new FileInputStream("/proc/"+pid+"/cmdline")){byte[] b=new byte[512];int n=in.read(b);if(n<1)return "";int end=0;while(end<n&&b[end]!=0)end++;return new String(b,0,end,"UTF-8");}catch(Exception e){return "";}}
    private static boolean belongs(int pid){String name=processName(pid);if(owned.contains(pid)){if(name.isEmpty()||name.startsWith(BROWSER))return true;owned.remove(pid);return false;}if(name.startsWith(BROWSER)){owned.add(pid);add(lifecycle,"renderer_seen pid="+pid,80);return true;}return false;}
    private static void scan(){
        File[] entries=new File("/proc").listFiles();if(entries==null)return;
        for(File e:entries){String n=e.getName();if(n.matches("\\d+"))belongs(Integer.parseInt(n));}
    }
    static String accept(String line,boolean ownedPid){
        Matcher m=ROW.matcher(line);if(!m.matches())return null;
        String tag=m.group(3).trim(),message=m.group(4);
        boolean system=Arrays.asList("ActivityManager","ActivityTaskManager","lmkd","tombstoned").contains(tag)&&message.contains(BROWSER);
        if(!system&&(!ownedPid||!TAGS.contains(tag)))return null;
        // Console text can contain page content even in a browser process. Own source only.
        if(tag.equals("chromium")&&message.contains("CONSOLE:")&&!message.contains(ExtensionConfig.ID)&&!message.contains(ExtensionConfig.LEGACY_ID))return null;
        if(tag.equals("chromium")&&!message.contains(ExtensionConfig.ID)&&!message.contains(ExtensionConfig.LEGACY_ID)&&!message.matches("(?i).*(renderer process|render process|extension|CHECK failed|FATAL).*"))return null;
        return redact(line);
    }
    private static String exitInfo(){
        Process p=null;StringBuilder out=new StringBuilder();
        try{
            p=new ProcessBuilder("dumpsys","activity","exit-info",BROWSER).redirectErrorStream(true).start();
            final Process child=p;Thread killer=new Thread(()->{try{Thread.sleep(3000);child.destroy();}catch(Exception ignored){}},"exit-info-deadline");killer.setDaemon(true);killer.start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream()))){String row;int n=0;
                while((row=reader.readLine())!=null&&n++<250){
                    if(row.matches("(?i).*\\b(reason|status|timestamp|importance|pss|rss|description|process|pid|processName)=.*"))out.append(redact(row)).append('\n');
                    if(out.length()>16000)break;
                }
            }
        }catch(Exception e){return "unavailable: "+e.getClass().getSimpleName();}finally{if(p!=null)p.destroy();}
        return out.length()==0?"No matching exit records":out.toString();
    }
    private static JSONArray copy(List<String> source){synchronized(source){return new JSONArray(new ArrayList<>(source));}}
    private static void emit(long started,String before,String after,boolean complete){
        try{System.out.println(new JSONObject().put("version",BridgeContract.BUILD).put("complete",complete)
            .put("durationMs",System.currentTimeMillis()-started)
            .put("scope","Browser processes, associated renderers, browser lifecycle and crash records; no profile files or unrelated app logs")
            .put("knownBrowserPidCount",owned.size()).put("processLifecycle",copy(lifecycle))
            .put("exitsBefore",before).put("exitsAfter",after).put("logcat",copy(lines)));System.out.flush();}catch(Exception ignored){}
    }
    public static void main(String[] args){
        Process log=null;String exitsBefore=exitInfo();long started=System.currentTimeMillis();
        try{
            scan();
            log=new ProcessBuilder("logcat","-b","main","-b","system","-b","crash","-v","threadtime","-T","2000").redirectErrorStream(true).start();
            final Process stream=log;
            Thread reader=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(stream.getInputStream()))){String line;while((line=r.readLine())!=null){Matcher m=ROW.matcher(line);if(!m.matches())continue;int pid=Integer.parseInt(m.group(1));String safe=accept(line,belongs(pid));if(safe!=null)add(lines,safe,180);}}catch(Exception ignored){}},"InternetTools-log-reader");reader.setDaemon(true);reader.start();
            System.out.println("READY");System.out.flush();
            // Parent closes stdin to end this session; hard limit also applies if parent dies.
            while(System.currentTimeMillis()-started<90000){if(System.in.available()>0)break;scan();emit(started,exitsBefore,"Capture in progress",false);Thread.sleep(2000);}
        }catch(Exception e){add(lifecycle,"collector_error="+e.getClass().getSimpleName(),80);}
        finally{if(log!=null){log.destroy();try{if(!log.waitFor(2,TimeUnit.SECONDS))log.destroyForcibly();}catch(Exception ignored){}}}
        emit(started,exitsBefore,exitInfo(),true);
    }
}
