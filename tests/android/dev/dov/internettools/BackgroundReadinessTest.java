package dev.dov.internettools;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class BackgroundReadinessTest {
    @Test public void interactiveDocumentCanFinishItsOriginalStartup()throws Exception {
        int[] reads={0},pauses={0};
        int checks=BackgroundReadiness.await(()->{reads[0]++;return new JSONObject().put("corePresent",reads[0]>=3).put("readyToFilter",reads[0]>=4).put("documentReadyState","interactive");},()->pauses[0]++);
        assertEquals(4,checks);assertEquals(4,reads[0]);assertEquals(3,pauses[0]);
    }
    @Test public void missingCoreDoesNotWaitForever()throws Exception {
        int[] pauses={0};JSONObject state=new JSONObject().put("corePresent",false).put("documentReadyState","interactive");
        assertEquals(16,BackgroundReadiness.await(()->state,()->pauses[0]++));assertEquals(15,pauses[0]);assertFalse(state.has("readyToFilter"));
    }
    @Test public void readyCoreDoesNotWaitAndStalledCoreIsNeverForcedReady()throws Exception {
        int[] pauses={0};JSONObject state=new JSONObject().put("corePresent",true).put("readyToFilter",true);
        assertEquals(1,BackgroundReadiness.await(()->state,()->pauses[0]++));assertEquals(0,pauses[0]);
        state.put("readyToFilter",false);assertEquals(16,BackgroundReadiness.await(()->state,()->pauses[0]++));assertFalse(state.getBoolean("readyToFilter"));
    }
}
