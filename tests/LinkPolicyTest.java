import dev.dov.internettools.LinkPolicy;

public final class LinkPolicyTest {
    public static void main(String[] args) {
        check(true, "android.intent.action.VIEW", "https", null, false, false);
        check(true, "android.intent.action.VIEW", "http", "com.whatsapp", false, false);
        check(true, "android.intent.action.VIEW", "HTTPS", "org.telegram.messenger", false, false);
        check(false, "com.samsung.android.intent.action.OPEN_NEW_SECRET_TAB", "https", null, false, false);
        check(false, "android.intent.action.MAIN", null, null, false, false);
        check(false, "android.intent.action.VIEW", "content", null, false, false);
        check(false, "android.intent.action.VIEW", "file", null, false, false);
        check(false, "android.intent.action.VIEW", "javascript", null, false, false);
        check(false, "android.intent.action.VIEW", "https", LinkPolicy.BROWSER, false, false);
        check(false, "android.intent.action.VIEW", "https", null, true, false);
        check(false, "android.intent.action.VIEW", "https", null, false, true);
        System.out.println("PASS: 11 external-link routing scenarios");
    }
    static void check(boolean expected, String a, String s, String id, boolean drag, boolean win) {
        if (LinkPolicy.isExternalWebLink(a,s,id,drag,win) != expected) throw new AssertionError(a+" "+s+" "+id);
    }
}
