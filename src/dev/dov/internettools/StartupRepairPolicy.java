package dev.dov.internettools;
import org.json.JSONObject;

final class StartupRepairPolicy {
    static boolean needsCache(JSONObject result) {
        JSONObject startup=result.optJSONObject("startup");
        if(startup==null)return false;
        JSONObject stats=startup.optJSONObject("supportStats");
        return "runtime_incomplete".equals(result.optString("state")) && "1.75.0".equals(result.optString("version"))
            && Boolean.FALSE.equals(result.opt("readyToFilter")) && startup.optBoolean("backgroundAccessible")
            && startup.optBoolean("corePresent") && "responded".equals(startup.optString("storageRead"))
            && "timeout".equals(startup.optString("managedStorageRead"))
            && Boolean.FALSE.equals(startup.opt("managedCachePresent")) && startup.optInt("selectedListCount",-1)==0
            && (stats==null||!stats.has("cacheBackend"));
    }
    static boolean firstInstallNeedsReload(JSONObject result, JSONObject initial, JSONObject repair, boolean attempted) {
        if(attempted||initial==null||repair==null)return false;
        String state=repair.optString("state");
        if(!state.equals("managed_cache_initialized")&&!state.equals("managed_cache_already_present"))return false;
        JSONObject before=initial.optJSONObject("startup"),after=result.optJSONObject("startup");
        if(before==null||after==null)return false;
        JSONObject stats=after.optJSONObject("supportStats");
        return "runtime_incomplete".equals(result.optString("state")) && Boolean.FALSE.equals(result.opt("readyToFilter"))
            && !before.has("storedVersion") && "1.75.0".equals(after.optString("storedVersion"))
            && after.optBoolean("managedCachePresent") && after.optInt("selectedListCount",0)>0
            && stats!=null&&stats.has("cacheBackend");
    }
}
