package dev.dov.internettools;
import static org.junit.Assert.*;
import org.junit.Test;
public class NativeActivationTest {
    static final class Native implements NativeActivation.Backend {
        boolean enabled,terminated,async;int reviveCalls,enableCalls;
        public boolean enabled(){return enabled;}
        public void revive(){reviveCalls++;if(terminated){terminated=false;enabled=true;}}
        public void enable(){enableCalls++;if(!async)enabled=true;}
    }
    @Test public void repeatedSetupDoesNotMutateAnEnabledRuntime(){Native n=new Native();n.enabled=true;assertEquals("already_enabled",NativeActivation.request(n));assertEquals("already_enabled",NativeActivation.request(n));assertEquals(0,n.reviveCalls);assertEquals(0,n.enableCalls);}
    @Test public void terminatedRuntimeIsRevivedWithoutRedundantEnable(){Native n=new Native();n.terminated=true;assertEquals("revived",NativeActivation.request(n));assertTrue(n.enabled);assertEquals(1,n.reviveCalls);assertEquals(0,n.enableCalls);}
    @Test public void disabledRuntimeIsEnabledOnceThenLeftRunning(){Native n=new Native();assertEquals("enable_requested",NativeActivation.request(n));assertTrue(n.enabled);NativeActivation.request(n);assertEquals(1,n.enableCalls);assertEquals(1,n.reviveCalls);}
    @Test public void asynchronousEnableDoesNotInventAnEnabledResult(){Native n=new Native();n.async=true;assertEquals("enable_requested",NativeActivation.request(n));assertFalse(n.enabled);assertEquals(1,n.enableCalls);}
}
