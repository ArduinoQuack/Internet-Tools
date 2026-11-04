package dev.dov.internettools;

public final class LinkPolicy {
    public static final String BROWSER = "com.sec.android.app.sbrowser";
    private LinkPolicy() {}

    // Browser drag/split-window operations and manual Secret shortcuts stay internal.
    public static boolean isExternalWebLink(String action, String scheme, String appId,
                                            boolean browserDrag, boolean otherWindow) {
        return "android.intent.action.VIEW".equals(action)
            && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
            && !BROWSER.equals(appId) && !browserDrag && !otherWindow;
    }
}
