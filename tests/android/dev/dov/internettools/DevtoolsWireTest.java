package dev.dov.internettools;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class DevtoolsWireTest {
    private byte[] frame(int first,String text)throws Exception {
        byte[] b=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(first);
        if(b.length<126)out.write(b.length);else{out.write(126);out.write(b.length>>8);out.write(b.length);}out.write(b);return out.toByteArray();
    }
    @Test public void readsFragmentedUtf8AndAnswersPing()throws Exception {
        ByteArrayOutputStream incoming=new ByteArrayOutputStream();incoming.write(frame(0x01,"{\"state\":"));incoming.write(frame(0x89,"ping"));incoming.write(frame(0x80,"\"ready ✓\"}"));
        ByteArrayOutputStream outgoing=new ByteArrayOutputStream();DevtoolsWire wire=new DevtoolsWire(new ByteArrayInputStream(incoming.toByteArray()),outgoing);
        assertEquals("{\"state\":\"ready ✓\"}",wire.receive());assertEquals(0x8a,outgoing.toByteArray()[0]&255);assertTrue((outgoing.toByteArray()[1]&128)!=0);
    }
    @Test public void clientFramesAreMaskedAndPayloadSurvivesBothLengthEncodings()throws Exception {
        for(int size:new int[]{4,300,70000}){
            char[] chars=new char[size];Arrays.fill(chars,'x');String message=new String(chars);
            ByteArrayOutputStream out=new ByteArrayOutputStream();new DevtoolsWire(new ByteArrayInputStream(new byte[0]),out).send(message);
            assertTrue((out.toByteArray()[1]&128)!=0);
            assertEquals(message,new DevtoolsWire(new ByteArrayInputStream(out.toByteArray()),new ByteArrayOutputStream()).receive());
        }
    }
    @Test public void boundedHttpBodyAndHandshakeValidation()throws Exception {
        String json="[{\"type\":\"background_page\"}]";
        String http="HTTP/1.1 200 OK\r\nContent-Length: "+json.length()+"\r\n\r\n"+json;
        DevtoolsWire wire=new DevtoolsWire(new ByteArrayInputStream(http.getBytes(StandardCharsets.US_ASCII)),new ByteArrayOutputStream());
        assertEquals(json,wire.body(wire.headers()));
        Map<String,String> headers=new HashMap<>();headers.put(":status","HTTP/1.1 101 Switching Protocols");
        headers.put("sec-websocket-accept",Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((wire.handshakeKey+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII))));
        wire.upgradeAccepted(headers);
        headers.put("sec-websocket-accept","wrong");try{wire.upgradeAccepted(headers);fail();}catch(IOException expected){}
        headers.put("content-length",String.valueOf(DevtoolsWire.LIMIT+1));try{wire.body(headers);fail();}catch(IOException expected){}
    }
    @Test public void closedOrOversizedFramesFailInsteadOfWaitingForever()throws Exception {
        try{new DevtoolsWire(new ByteArrayInputStream(new byte[]{(byte)0x88,0}),new ByteArrayOutputStream()).receive();fail();}catch(EOFException expected){}
        byte[] oversized={(byte)0x81,127,0,0,0,0,1,0,0,0};
        try{new DevtoolsWire(new ByteArrayInputStream(oversized),new ByteArrayOutputStream()).receive();fail();}catch(IOException expected){}
    }
}
