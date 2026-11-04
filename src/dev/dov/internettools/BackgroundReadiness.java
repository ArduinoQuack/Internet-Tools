package dev.dov.internettools;

import org.json.JSONObject;

/** Let the original deferred startup run; never manufacture a core or set readiness. */
final class BackgroundReadiness {
    interface Observation { JSONObject read() throws Exception; }
    interface Pause { void pause() throws Exception; }
    static int await(Observation observation,Pause pause)throws Exception {
        for(int check=1;check<=16;check++){
            JSONObject state=observation.read();
            if(state.optBoolean("corePresent")&&state.optBoolean("readyToFilter"))return check;
            if(check<16)pause.pause();
        }
        return 16;
    }
}
