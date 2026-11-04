package dev.dov.internettools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

final class SettingsStore {
    static final String PRIVACY="samsung_privacy", NORMAL_LINKS="normal_links";
    static final Uri URI=Uri.parse("content://dev.dov.internettools.status/settings");
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",0);}
    static Bundle snapshot(Context c){Bundle b=new Bundle();b.putBoolean(PRIVACY,prefs(c).getBoolean(PRIVACY,true));b.putBoolean(NORMAL_LINKS,prefs(c).getBoolean(NORMAL_LINKS,true));return b;}
    static void set(Context c,String key,boolean value){
        if(!PRIVACY.equals(key)&&!NORMAL_LINKS.equals(key))throw new IllegalArgumentException("Unknown setting");
        prefs(c).edit().putBoolean(key,value).putBoolean("restart_pending",true).commit();
        c.getContentResolver().notifyChange(URI,null);
    }
}
