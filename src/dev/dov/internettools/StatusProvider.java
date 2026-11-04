package dev.dov.internettools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.content.pm.PackageManager;

// Reports contain setup status only. No URLs, titles, screenshots, tokens, or page content.
public final class StatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle b) {
        requireBrowser();
        if("settings".equals(method))return SettingsStore.snapshot(getContext());
        if ("uboEvent".equals(method) && b != null) {
            String record = b.getString("state", "unknown") + "\n" + b.getString("detail", "") + "\nReported: " + new java.util.Date();
            getContext().getSharedPreferences("status", 0).edit().putString("uBlock Origin", record).apply();
            return Bundle.EMPTY;
        }
        if (!"report".equals(method) || b == null) throw new IllegalArgumentException("Unsupported method");
        String proc = b.getString("process", "unknown");
        String record = "Hook build: " + b.getString("hookBuild", "older / unknown") + "\nBrowser " + b.getString("browserVersion", "unknown")
            + "\nBrowser PID: " + b.getInt("browserPid",-1)
            + "\nHooks installed: " + b.getInt("installed")
            + "\nMissing targets: " + (b.getString("missing", "").isEmpty() ? "none" : b.getString("missing"))
            + "\n" + b.getString("capabilities", "Extension capability probe not reported")
            + "\nReported: " + new java.util.Date();
        getContext().getSharedPreferences("status", 0).edit().putString(proc, record).apply();
        return Bundle.EMPTY;
    }
    private void requireBrowser() {
        try {
            int uid = getContext().getPackageManager().getApplicationInfo(LinkPolicy.BROWSER, 0).uid;
            if (Binder.getCallingUid() != uid) throw new SecurityException("Browser reports only");
        } catch (PackageManager.NameNotFoundException e) { throw new SecurityException("Browser missing"); }
    }
    @Override public String getType(Uri u) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new SecurityException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new SecurityException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new SecurityException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new SecurityException(); }
}
