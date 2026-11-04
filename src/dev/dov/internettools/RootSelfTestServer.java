package dev.dov.internettools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Short-lived root loopback server. Serves synthetic test data only, never remote content. */
final class RootSelfTestServer implements AutoCloseable {
    final ServerSocket server;final String nonce="a"+UUID.randomUUID().toString().replace("-","").substring(0,24);
    volatile JSONObject result;volatile boolean stopped,controlReceived;volatile int blockedReceived;
    RootSelfTestServer()throws IOException {
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);server.setSoTimeout(1000);
        Thread worker=new Thread(()->{while(!stopped){try(Socket s=server.accept()){serve(s);}catch(SocketTimeoutException ignored){}catch(Exception ignored){}}},"InternetTools-loopback-test");worker.setDaemon(true);worker.start();
    }
    String origin(){return "http://127.0.0.1:"+server.getLocalPort();}
    private void serve(Socket s)throws Exception {
        s.setSoTimeout(2000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));
        String request=in.readLine();if(request==null||request.length()>4096)return;String[] parts=request.split(" ");if(parts.length<2||!"GET".equals(parts[0]))return;
        for(int count=0;count<40;count++){String line=in.readLine();if(line==null||line.isEmpty())break;if(line.length()>4096)return;}
        URI uri=new URI(parts[1]);String path=uri.getPath();String base="/"+nonce+"/",type="text/plain",body="not found";int code=404;
        if(path.equals(base)){
            code=200;type="text/html";String prop="it"+nonce;
            body="<!doctype html><meta name=viewport content='width=device-width'><title>Internet Tools test</title><style>body{background:#000;color:#ddd;font:18px sans-serif;padding:32px}</style><p>Checking uBlock Origin…</p><div id=c class='"+prop+"'>Synthetic filter test</div><script src='control.js'></script><script src='blocked.js'></script><script>addEventListener('load',()=>setTimeout(()=>{const q=new URLSearchParams({control:window.itControl===true,blocked:window.itBlocked!==true,cosmetic:getComputedStyle(document.getElementById('c')).display==='none',scriptlet:window['"+prop+"']===true});fetch('result?'+q).catch(()=>{});},1200));</script>";
        }else if(path.equals(base+"control.js")){controlReceived=true;code=200;type="application/javascript";body="window.itControl=true;";
        }else if(path.equals(base+"blocked.js")){blockedReceived++;code=200;type="application/javascript";body="window.itBlocked=true;";
        }else if(path.equals(base+"result")){
            JSONObject seen=new JSONObject();String query=uri.getRawQuery();if(query!=null)for(String item:query.split("&")){String[] p=item.split("=",2);if(p.length==2&&Arrays.asList("control","blocked","cosmetic","scriptlet").contains(p[0]))seen.put(p[0],"true".equals(p[1]));}
            result=seen;code=200;body="ok";
        }
        byte[] data=body.getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" OK\r\nContent-Type: "+type+"; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+data.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
    }
    JSONObject await()throws Exception {for(int i=0;i<100&&result==null;i++)Thread.sleep(100);JSONObject r=result==null?new JSONObject().put("timeout",true):result;r.put("controlReceived",controlReceived).put("blockedResourceReachedServer",blockedReceived>0);return r;}
    public void close(){stopped=true;try{server.close();}catch(IOException ignored){}}
}
