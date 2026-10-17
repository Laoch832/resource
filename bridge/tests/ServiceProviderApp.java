package com.sony.songpal.earcapture.j2objc.immersiveaudio;
import java.util.Map;
public class ServiceProviderApp {
    public enum LaunchType { URL_SCHEME, ADJUST, ONE_LINK, PRE_INSTALL, NONE }
    public final String name, scheme;
    private final String packageName;
    public ServiceProviderApp(String name, String pkg, String icon, String scheme, String url,
                              String urlType, LaunchType launchType, Map<?, ?> params) {
        this.name = name; this.packageName = pkg; this.scheme = scheme;
    }
    public String g() { return packageName; }
    public String h() { return scheme; }
}
