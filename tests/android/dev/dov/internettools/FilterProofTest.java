package dev.dov.internettools;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class FilterProofTest {
    private JSONObject page()throws Exception{return new JSONObject("{\"control\":true,\"controlReceived\":true,\"blocked\":true,\"blockedResourceReachedServer\":false,\"cosmetic\":true,\"scriptlet\":true}");}
    private JSONObject background()throws Exception{return new JSONObject("{\"blockedByUbo\":true,\"cleanupVerified\":true,\"testTabClosed\":true}");}
    @Test public void allIndependentChecksAreRequired()throws Exception{
        assertTrue(FilterProof.passed(page(),background()));
        for(String key:new String[]{"control","controlReceived","blocked","cosmetic","scriptlet"})assertFalse(FilterProof.passed(page().put(key,false),background()));
        assertFalse(FilterProof.passed(page().put("blockedResourceReachedServer",true),background()));
        assertFalse(FilterProof.passed(page(),background().put("blockedByUbo",false)));
        assertFalse(FilterProof.passed(page(),background().put("cleanupVerified",false)));
        assertFalse(FilterProof.passed(page(),background().put("testTabClosed",false)));
        assertFalse(FilterProof.passed(new JSONObject(),new JSONObject()));
    }
    @Test public void loopbackServerDistinguishesObservedRequestsFromPageClaims()throws Exception{
        try(RootSelfTestServer server=new RootSelfTestServer()){
            java.net.URL base=new java.net.URL(server.origin()+"/"+server.nonce+"/");
            try(java.io.InputStream in=new java.net.URL(base,"control.js").openStream()){assertTrue(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("itControl"));}
            try(java.io.InputStream in=new java.net.URL(base,"blocked.js").openStream()){assertTrue(in.read()>0);}
            try(java.io.InputStream in=new java.net.URL(base,"result?control=true&blocked=true&cosmetic=true&scriptlet=true").openStream()){assertTrue(in.read()>0);}
            JSONObject report=server.await();assertTrue(report.getBoolean("controlReceived"));assertTrue(report.getBoolean("blockedResourceReachedServer"));
            assertFalse("The page cannot claim a request was blocked if the server received it",FilterProof.passed(report,background()));
        }
    }
}
