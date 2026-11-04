package dev.dov.internettools;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;

/** Ephemeral root tool. Only this build's extension and a temporary loopback test are accessed. */
public final class BackgroundInspectorMain {
    private static final String ORIGIN="chrome-extension://"+ExtensionConfig.ID+"/";
    private static volatile String lastSnapshotJson="{}";
    private static volatile String phase="starting";private static int serial,attempts;
    private static final Set<String> backgroundInstances=new HashSet<>();
    private static final JSONArray runtimeErrors=new JSONArray();
    private static final JSONArray timeline=new JSONArray(),passive=new JSONArray(),contextEvents=new JSONArray();
    private static int transportLosses;
    private static final long started=System.currentTimeMillis();
    private static final java.util.concurrent.atomic.AtomicBoolean emitted=new java.util.concurrent.atomic.AtomicBoolean();
    static final class Connection implements AutoCloseable {
        final LocalSocket socket=new LocalSocket();final DevtoolsWire wire;String body;
        Connection(String path,boolean websocket)throws Exception{
            DevtoolsWire connected;
            try{socket.connect(new LocalSocketAddress("Terrace_devtools_remote",LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(5000);
                connected=new DevtoolsWire(new BufferedInputStream(socket.getInputStream()),socket.getOutputStream());connected.http(path,websocket);
                Map<String,String> h=connected.headers();if(websocket)connected.upgradeAccepted(h);else{if(!h.get(":status").contains(" 200 "))throw new IOException("http_error");body=connected.body(h);}
            }catch(Exception e){try{socket.close();}catch(Exception ignored){}throw e;}wire=connected;
        }
        public void close(){try{socket.close();}catch(IOException ignored){}}
    }
    private static JSONArray targets()throws Exception{try(Connection c=new Connection("/json/list",false)){return new JSONArray(c.body);}}
    private static String targetPath(JSONObject target)throws Exception{
        String path=new URI(target.getString("webSocketDebuggerUrl")).getRawPath();if(path==null||!path.matches("/devtools/(page|background_page)/[A-Za-z0-9_-]+"))throw new IOException("invalid_target_path");return path;
    }
    private static JSONObject command(Connection c,String method,JSONObject params)throws Exception{
        int id=++serial;c.wire.send(new JSONObject().put("id",id).put("method",method).put("params",params).toString());
        for(int i=0;i<2000;i++){JSONObject reply=new JSONObject(c.wire.receive());
            if("Runtime.exceptionThrown".equals(reply.optString("method"))&&runtimeErrors.length()<8){
                JSONObject d=reply.optJSONObject("params");d=d==null?null:d.optJSONObject("exceptionDetails");
                if(d!=null&&d.optString("url").startsWith(ORIGIN)){
                    JSONObject ex=d.optJSONObject("exception");String name=ex==null?"Error":ex.optString("className","Error");
                    if(!name.matches("[A-Za-z]{1,40}"))name="Error";
                    runtimeErrors.put(new JSONObject().put("name",name).put("source",d.optString("url").substring(ORIGIN.length())).put("line",d.optInt("lineNumber")));
                }
            }
            String event=reply.optString("method");
            if((event.equals("Inspector.targetCrashed")||event.equals("Inspector.detached")||event.equals("Runtime.executionContextsCleared"))&&contextEvents.length()<24)
                contextEvents.put(new JSONObject().put("event",event).put("atMs",System.currentTimeMillis()-started));
            if(reply.optInt("id")==id){if(reply.has("error")){String message=reply.getJSONObject("error").optString("message","").toLowerCase(java.util.Locale.ROOT);if(message.contains("context")||message.contains("target closed")||message.contains("session closed"))throw new IOException("context_restarted");throw new IOException("protocol_"+method+"_failed");}return reply.getJSONObject("result");}}
        throw new IOException("protocol_event_limit");
    }
    private static JSONObject evaluate(Connection c,String script)throws Exception{
        JSONObject r=command(c,"Runtime.evaluate",new JSONObject().put("expression",script).put("awaitPromise",true).put("returnByValue",true).put("timeout",12000));
        if(r.has("exceptionDetails"))throw new IOException("extension_script_failed");Object value=r.getJSONObject("result").opt("value");if(!(value instanceof JSONObject))throw new IOException("extension_result_missing");return (JSONObject)value;
    }
    private static String asset(String apk,String name)throws Exception{
        try(ZipFile z=new ZipFile(apk);InputStream in=z.getInputStream(z.getEntry("assets/"+name));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>131072)throw new IOException("asset_limit");out.write(b,0,n);}return out.toString("UTF-8");
        }
    }
    private static boolean retryable(IOException e){String m=e.getMessage();return m==null||!(m.contains("mismatch")||m.startsWith("invalid_")||m.startsWith("protocol_")||m.startsWith("extension_script"));}
    private static JSONObject functionalTest(Connection c,JSONObject snapshot)throws Exception{
        phase="test_real_filtering";try(RootSelfTestServer server=new RootSelfTestServer()){
            String prefix="globalThis.__internetToolsRuntime.__internetToolsEngine";
            evaluate(c,"(async()=>{return await "+prefix+".beginTest("+JSONObject.quote(server.origin())+","+JSONObject.quote(server.nonce)+");})()");
            JSONObject observed;JSONObject cleanup;
            try{observed=server.await();}finally{phase="restore_test_rules";cleanup=evaluate(c,"(async()=>{return await "+prefix+".finishTest("+JSONObject.quote(server.nonce)+");})()");}
            observed.put("blockedByUbo",cleanup.optBoolean("blockedByUbo")).put("cleanupVerified",cleanup.optBoolean("cleanupVerified")).put("testTabClosed",cleanup.optBoolean("testTabClosed"));
            boolean pass=FilterProof.passed(observed,cleanup);
            snapshot.put("functionalTest",observed).put("requestBlockingVerified",pass).put("state",pass?"blocking_verified":"self_test_failed");return snapshot;
        }
    }
    public static void main(String[] args){
        Thread watchdog=new Thread(()->{try{Thread.sleep(60000);emit(new JSONObject(lastSnapshotJson).put("state","background_inspection_timeout").put("stage",phase).put("requestBlockingVerified",false));System.exit(0);}catch(Exception ignored){}},"InternetTools-deadline");watchdog.setDaemon(true);watchdog.start();
        JSONObject result=new JSONObject();Connection current=null;
        try{
            if(args.length!=2||!(args[0].equals("probe")||args[0].equals("open")))throw new IOException("invalid_arguments");
            String script=asset(args[1],"ubo-background-probe.js");
            // Observe target churn before any debugger attachment, with no target creation or navigation.
            phase="observe_without_debugger";
            for(int observation=0;observation<6;observation++){
                try{JSONArray all=targets();JSONArray backgrounds=new JSONArray();
                    for(int i=0;i<all.length();i++){JSONObject t=all.optJSONObject(i);
                        if(t!=null&&t.optString("url").equals(ORIGIN+"background.html"))backgrounds.put(t.optString("id"));}
                    passive.put(new JSONObject().put("atMs",System.currentTimeMillis()-started).put("backgroundIds",backgrounds));
                }catch(IOException e){transportLosses++;}
                Thread.sleep(500);
            }
            long deadline=System.currentTimeMillis()+30000;JSONObject last=null;boolean ready=false;
            while(System.currentTimeMillis()<deadline&&!ready){
                phase="find_extension_runtime";attempts++;
                try{
                    JSONArray all=targets();List<JSONObject> owned=new ArrayList<>();
                    for(int i=0;i<all.length();i++){JSONObject t=all.optJSONObject(i);if(t!=null&&t.optString("url").startsWith(ORIGIN))owned.add(t);}
                    owned.sort(Comparator.comparingInt(t->t.optString("url").equals(ORIGIN+"background.html")?0:"background_page".equals(t.optString("type"))?1:2));
                    for(JSONObject target:owned){
                        if(ready)break;
                        Connection candidate=new Connection(targetPath(target),true);boolean retain=false;
                        try{
                            phase="read_original_background";command(candidate,"Runtime.enable",new JSONObject());
                            for(int wait=0;System.currentTimeMillis()<deadline;wait++){
                                last=evaluate(candidate,script);lastSnapshotJson=last.toString();JSONObject startup=last.optJSONObject("startup");if(startup!=null&&!startup.optString("instance").isEmpty())backgroundInstances.add(startup.optString("instance"));String state=last.optString("state");
                                if(timeline.length()<40)timeline.put(new JSONObject().put("atMs",System.currentTimeMillis()-started).put("state",state)
                                    .put("instance",startup==null?"":startup.optString("instance")).put("phase",startup==null?"":startup.optString("phase"))
                                    .put("lists",startup==null?0:startup.optInt("selectedListCount")));
                                if(state.equals("runtime_ready")||(args[0].equals("open")&&last.optBoolean("dashboardAvailable"))){current=candidate;retain=ready=true;result=last;break;}
                                if(state.equals("private_background_skipped")||state.equals("unexpected_extension_build")||state.equals("background_core_missing")||state.equals("unexpected_background_target"))break;
                                Thread.sleep(400);
                            }
                        }finally{if(!retain)candidate.close();}
                    }
                }catch(IOException transientFailure){transportLosses++;if(!retryable(transientFailure))throw transientFailure;result.put("lastTransportError",transientFailure.getClass().getSimpleName());}
                if(!ready)Thread.sleep(400);
            }
            if(!ready){if(last!=null)result=last;else result.put("state","background_target_missing");}
            else if(args[0].equals("probe"))result=functionalTest(current,result);
            else{
                phase="open_dashboard";result.put("dashboardRequested",true);
                try{evaluate(current,"(async()=>{return await globalThis.__internetToolsRuntime.__internetToolsEngine.openDashboard();})()");}catch(IOException navigationFailure){result.put("navigationRequestError",navigationFailure.getClass().getSimpleName());}
                boolean opened=false;
                for(int wait=0;wait<16&&!opened;wait++){
                    Thread.sleep(200);JSONArray all=targets();for(int i=0;i<all.length();i++){JSONObject t=all.getJSONObject(i);if(!t.optString("url").startsWith(ORIGIN+"dashboard.html"))continue;
                        try(Connection view=new Connection(targetPath(t),true)){JSONObject doc=evaluate(view,"({live:location.protocol==='chrome-extension:'&&location.hostname==='"+ExtensionConfig.ID+"'&&!!document.querySelector('script[src=\"js/dashboard.js\"]')&&!document.querySelector('#main-frame-error,#error-page,.neterror')})");if(doc.optBoolean("live")){opened=true;break;}}
                    }
                }
                result.put("state",opened?"dashboard_opened":"dashboard_navigation_unverified");
            }
        }catch(Throwable e){try{String message=e instanceof IOException&&e.getMessage()!=null?e.getMessage():"";if(!message.matches("[A-Za-z0-9_ .:()/-]{1,160}"))message="";result.put("state","background_inspection_failed").put("stage",phase).put("error",e.getClass().getSimpleName()+(message.isEmpty()?"":": "+message));}catch(Exception ignored){}}
        finally{if(current!=null)current.close();}
        try{result.put("inspectorVersion","1.0.1").put("connectionAttempts",attempts).put("backgroundInstancesSeen",backgroundInstances.size()).put("runtimeErrors",runtimeErrors).put("transportLosses",transportLosses).put("passiveBeforeAttach",passive).put("startupTimeline",timeline).put("contextEvents",contextEvents);if(!result.has("requestBlockingVerified"))result.put("requestBlockingVerified",false);}catch(Exception ignored){}
        emit(result);System.exit(0);
    }
    private static void emit(JSONObject result){if(emitted.compareAndSet(false,true)){System.out.println(result.toString());System.out.flush();}}
}
