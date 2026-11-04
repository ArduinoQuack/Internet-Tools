package dev.dov.internettools;

final class OperationState {
    static boolean busy(String state) {
        switch (state) {
            case "connecting": case "received": case "running": case "copying_verified_crx":
            case "install_requested": case "installed_not_verified": case "opening_dashboard":
            case "starting_extension_service": case "opening_extension": case "restarting_extension":
            case "checking_background": case "repairing_startup": case "force_stopping": return true;
            default: return false;
        }
    }
    static long budget(String command) {
        if ("install".equals(command)) return 120000;
        if ("runtime_open".equals(command)) return 60000;
        if ("verify".equals(command)) return 90000;
        if ("open".equals(command)||"undo_fix".equals(command)) return 25000;
        return 15000;
    }
}
