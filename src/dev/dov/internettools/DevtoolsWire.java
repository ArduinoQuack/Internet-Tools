package dev.dov.internettools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Minimal bounded HTTP/WebSocket transport shared by the on-device inspector and protocol tests. */
final class DevtoolsWire {
    static final int LIMIT=4*1024*1024;
    final InputStream in; final OutputStream out; final String handshakeKey;
    DevtoolsWire(InputStream in,OutputStream out){this.in=in;this.out=out;byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);handshakeKey=Base64.getEncoder().encodeToString(nonce);}
    void http(String path,boolean upgrade) throws Exception {
        String key=handshakeKey;
        String request="GET "+path+" HTTP/1.1\r\nHost: localhost\r\n"+(upgrade?"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\n":"Connection: close\r\n")+"\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));out.flush();
    }
    Map<String,String> headers() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int sequence=0;
        while(bytes.size()<32768){int b=in.read();if(b<0)throw new EOFException("http_headers_closed");bytes.write(b);
            sequence=(sequence<<8)|b;if(sequence==0x0d0a0d0a)break;}
        if(sequence!=0x0d0a0d0a)throw new IOException("http_headers_too_large");
        String[] lines=bytes.toString("US-ASCII").split("\r\n");Map<String,String> headers=new HashMap<>();headers.put(":status",lines[0]);
        for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0)headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
        return headers;
    }
    String body(Map<String,String> headers)throws Exception {
        int length=Integer.parseInt(headers.getOrDefault("content-length","-1"));
        if(length<0||length>LIMIT)throw new IOException("invalid_http_body_size");
        return new String(read(length),StandardCharsets.UTF_8);
    }
    void upgradeAccepted(Map<String,String> headers)throws Exception {
        if(!headers.get(":status").contains(" 101 "))throw new IOException("websocket_upgrade_rejected");
        String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((handshakeKey+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        if(!expected.equals(headers.get("sec-websocket-accept")))throw new IOException("websocket_accept_mismatch");
    }
    byte[] read(int length)throws IOException {byte[] b=new byte[length];int offset=0;while(offset<length){int n=in.read(b,offset,length-offset);if(n<0)throw new EOFException("transport_payload_closed");offset+=n;}return b;}
    void send(String message)throws IOException {frame(1,message.getBytes(StandardCharsets.UTF_8));}
    void frame(int opcode,byte[] data)throws IOException {
        if(data.length>LIMIT)throw new IOException("frame_too_large");
        out.write(0x80|opcode);int n=data.length;
        if(n<126)out.write(0x80|n);else if(n<65536){out.write(0x80|126);out.write(n>>8);out.write(n);}else{out.write(0x80|127);for(int i=7;i>=0;i--)out.write((int)(((long)n)>>(8*i)));}
        byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);out.write(mask);
        byte[] masked=new byte[n];for(int i=0;i<n;i++)masked[i]=(byte)(data[i]^mask[i%4]);out.write(masked);out.flush();
    }
    String receive()throws IOException {
        ByteArrayOutputStream message=new ByteArrayOutputStream();
        for(;;){int a=in.read(),b=in.read();if(a<0||b<0)throw new EOFException("websocket_header_closed");if((a&0x70)!=0)throw new IOException("unsupported_websocket_flags");
            long n=b&127;if(n==126){byte[] size=read(2);n=((size[0]&255)<<8)|(size[1]&255);}else if(n==127){n=0;for(byte x:read(8)){if(n>LIMIT)throw new IOException("frame_too_large");n=(n<<8)|(x&255);}}
            if(n>LIMIT||n<0)throw new IOException("frame_too_large");byte[] mask=(b&128)!=0?read(4):null;byte[] payload=read((int)n);
            if(mask!=null)for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];
            int op=a&15;if(op==8)throw new EOFException("websocket_closed");if(op==9){frame(10,payload);continue;}if(op==10)continue;
            if(op!=0&&op!=1)throw new IOException("unsupported_frame_type");if(message.size()+payload.length>LIMIT)throw new IOException("message_too_large");message.write(payload);
            if((a&128)!=0)return message.toString("UTF-8");
        }
    }
}
