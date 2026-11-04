package dev.dov.internettools;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class StartupRepairTest {
    private JSONObject stalled() throws Exception {
        return new JSONObject("{\"state\":\"runtime_incomplete\",\"version\":\"1.75.0\",\"readyToFilter\":false,\"startup\":{\"backgroundAccessible\":true,\"corePresent\":true,\"storageRead\":\"responded\",\"managedStorageRead\":\"timeout\",\"managedCachePresent\":false,\"selectedListCount\":0,\"supportStats\":{\"allReadyAfter\":\"?\"}}}");
    }
    @Test public void suppliedReportQualifiesButExistingOrUnknownCachesDoNot() throws Exception {
        JSONObject report=stalled();assertTrue(StartupRepairPolicy.needsCache(report));
        report.getJSONObject("startup").put("managedCachePresent",true);assertFalse(StartupRepairPolicy.needsCache(report));
        report.getJSONObject("startup").remove("managedCachePresent");assertFalse(StartupRepairPolicy.needsCache(report));
        report=stalled();report.getJSONObject("startup").put("managedStorageRead","responded");assertFalse(StartupRepairPolicy.needsCache(report));
        report=stalled();report.getJSONObject("startup").getJSONObject("supportStats").put("cacheBackend","indexedDB");assertFalse(StartupRepairPolicy.needsCache(report));
        report=stalled();report.put("readyToFilter",true);assertFalse(StartupRepairPolicy.needsCache(report));
        report=stalled();report.put("version","2.0.0");assertFalse(StartupRepairPolicy.needsCache(report));
    }
    @Test public void additionalReloadOnlyCoversProvenFirstInstallTransitionAndOnlyOnce() throws Exception {
        JSONObject initial=stalled(),after=stalled(),repair=new JSONObject("{\"state\":\"managed_cache_initialized\"}");
        after.getJSONObject("startup").put("storedVersion","1.75.0").put("managedCachePresent",true).put("selectedListCount",12)
            .getJSONObject("supportStats").put("cacheBackend","indexedDB");
        assertTrue(StartupRepairPolicy.firstInstallNeedsReload(after,initial,repair,false));
        assertFalse(StartupRepairPolicy.firstInstallNeedsReload(after,initial,repair,true));
        assertFalse(StartupRepairPolicy.firstInstallNeedsReload(after,initial,null,false));
        initial.getJSONObject("startup").put("storedVersion","1.75.0");
        assertFalse(StartupRepairPolicy.firstInstallNeedsReload(after,initial,repair,false));
        initial=stalled();after.getJSONObject("startup").put("selectedListCount",0);
        assertFalse(StartupRepairPolicy.firstInstallNeedsReload(after,initial,repair,false));
    }
    @Test public void oldLoadedHookIsRejectedBeforeItsResultCanClaimSuccess() {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("bridge",0);
        prefs.edit().putString("request","current").putBoolean("active",true).putString("state","connecting").putLong("sent",System.currentTimeMillis()).commit();
        new BridgeReceiver().onReceive(context,new android.content.Intent().putExtra(BridgeContract.REQUEST,"current")
            .putExtra("state","runtime_ready").putExtra("hookBuild","0.5.0"));
        assertEquals("hook_update_required",prefs.getString("state",""));assertFalse(prefs.getBoolean("active",true));
    }
}
