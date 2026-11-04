package dev.dov.internettools;
import org.json.JSONObject;
final class FilterProof {
    static boolean passed(JSONObject page,JSONObject background){
        return page.optBoolean("control")&&page.optBoolean("controlReceived")&&page.optBoolean("blocked")
            &&!page.optBoolean("blockedResourceReachedServer")&&page.optBoolean("cosmetic")&&page.optBoolean("scriptlet")
            &&background.optBoolean("blockedByUbo")&&background.optBoolean("cleanupVerified")&&background.optBoolean("testTabClosed");
    }
}
