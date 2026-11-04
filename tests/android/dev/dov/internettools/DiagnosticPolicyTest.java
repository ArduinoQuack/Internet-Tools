package dev.dov.internettools;
import static org.junit.Assert.*;
import org.junit.Test;
public class DiagnosticPolicyTest {
    private String line(String tag,String text){return "11-03 00:15:00.123  1234  1235 I "+tag+": "+text;}
    @Test public void unrelatedProcessAndGenericPageConsoleAreRejected(){
        assertNull(DiagnosticCollectorMain.accept(line("AndroidRuntime","private message"),false));
        assertNull(DiagnosticCollectorMain.accept(line("chromium","[INFO:CONSOLE] account data"),true));
        assertNull(DiagnosticCollectorMain.accept(line("OtherApp","account data"),true));
    }
    @Test public void ownLifecycleAndBrowserSystemDeathAreRetained(){
        assertNotNull(DiagnosticCollectorMain.accept(line("InternetToolsTrace","own_popup_dismissed"),true));
        assertNotNull(DiagnosticCollectorMain.accept(line("ActivityManager","Process com.sec.android.app.sbrowser:sandboxed_process0 has died"),false));
        assertNull(DiagnosticCollectorMain.accept(line("ActivityManager","Process other.app has died"),false));
    }
    @Test public void urlsAndCommonCredentialFormsAreRedacted(){
        String s=DiagnosticCollectorMain.redact("https://private.test/path?password=abc token=xyz Authorization:abc Cookie=mycookie user@example.com");
        assertFalse(s.contains("private.test"));assertFalse(s.contains("xyz"));assertFalse(s.contains("mycookie"));assertFalse(s.contains("user@example.com"));
    }
    @Test public void ownExtensionErrorsRetainLocationWithoutWebsite(){
        String s=DiagnosticCollectorMain.accept(line("chromium","chrome-extension://"+ExtensionConfig.ID+"/js/start-compat.js:123: TypeError at https://private.test/secret"),true);
        assertNotNull(s);assertTrue(s.contains("start-compat.js:123"));assertFalse(s.contains("private.test"));
    }
    @Test public void otherExtensionConsoleIsRejected(){assertNull(DiagnosticCollectorMain.accept(line("chromium","[INFO:CONSOLE:18] private other extension text source: chrome-extension://unrelated/background.js"),true));}
    @Test public void oversizedLinesAreBounded(){assertEquals(700,DiagnosticCollectorMain.redact(new String(new char[2000]).replace('\0',' ')).length());}
}
