package dev.dov.internettools;
import org.json.JSONArray;
import java.util.ArrayList;
final class BrowserTrace {
    static volatile boolean verbose;
    private static final ArrayList<String> entries=new ArrayList<>();
    static synchronized void add(String event){
        if(!event.matches("[A-Za-z0-9_]{1,100}"))return;
        String item=android.os.SystemClock.elapsedRealtime()+" "+event;
        if(entries.size()>=64)entries.remove(0);entries.add(item);
        if(verbose)android.util.Log.i("InternetToolsTrace",item);
    }
    static synchronized JSONArray snapshot(){return new JSONArray(entries);}
}
