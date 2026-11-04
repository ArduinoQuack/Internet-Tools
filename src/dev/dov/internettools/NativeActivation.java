package dev.dov.internettools;
/** An explicit setup/open/check revives or enables only our extension, once. */
final class NativeActivation {
    interface Backend { boolean enabled(); void revive(); void enable(); }
    static String request(Backend nativeApi){
        if(nativeApi.enabled())return "already_enabled";
        nativeApi.revive();
        if(nativeApi.enabled())return "revived";
        nativeApi.enable();
        return "enable_requested";
    }
}
