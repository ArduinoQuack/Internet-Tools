package dev.dov.internettools;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Read on attach and on a settings notification. No polling or world-readable preferences. */
final class BrowserSettings {
    static volatile boolean privacy=true,normalLinks=true;
    private static boolean observing;
    static void attach(Context context){
        Context app=context.getApplicationContext();refresh(app);
        if(observing)return;observing=true;
        app.getContentResolver().registerContentObserver(SettingsStore.URI,false,new ContentObserver(new Handler(Looper.getMainLooper())){
            @Override public void onChange(boolean changed){refresh(app);}
        });
    }
    private static void refresh(Context app){try{
        Bundle b=app.getContentResolver().call(SettingsStore.URI,"settings",null,null);
        if(b!=null){privacy=b.getBoolean(SettingsStore.PRIVACY,true);normalLinks=b.getBoolean(SettingsStore.NORMAL_LINKS,true);}
    }catch(RuntimeException ignored){}}
}
