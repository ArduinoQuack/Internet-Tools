package dev.dov.internettools;

/** Keep only this extension's source lines; redact any ordinary website URLs. */
final class ConsolePolicy {
    static String uboLine(String line) {
        String origin = "chrome-extension://"+ExtensionConfig.ID+"/";
        if (line == null || !line.contains(origin)) return null;
        String sanitized = line.replaceAll("https?://[^\\s\\\"'<>)]*", "<web-url>");
        return sanitized.substring(0, Math.min(sanitized.length(), 500));
    }
}
