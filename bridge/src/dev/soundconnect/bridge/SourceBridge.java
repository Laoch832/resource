package dev.soundconnect.bridge;

import java.lang.reflect.Constructor;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Local profile handoff. Sony's official optimization status is not forged. */
public final class SourceBridge {
    public static final String TARGET = "com.netease.cloudmusic";
    public static final String PROFILE_URI = "content://com.sony.songpal.mdr.netease360ra/hrtf";
    private static final String API = "com.sony.songpal.earcapture.j2objc.immersiveaudio.";

    private SourceBridge() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addProvider(List providers) {
        synchronized (providers) {
            try {
                Class<?> cls = Class.forName(API + "ServiceProviderApp");
                for (Object provider : providers) {
                    if (TARGET.equals(cls.getMethod("g").invoke(provider))) return;
                }
                Class<? extends Enum> launch = (Class<? extends Enum>) Class.forName(API + "ServiceProviderApp$LaunchType");
                Constructor<?> constructor = cls.getConstructor(String.class, String.class, String.class,
                        String.class, String.class, String.class, launch, Map.class);
                providers.add(constructor.newInstance("网易云音乐（Sony 耳形 R1）", TARGET, "", "netease360ra",
                        "https://music.163.com/download", "web", Enum.valueOf(launch, "URL_SCHEME"),
                        Collections.emptyMap()));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Sound Connect API does not match this patch", e);
            }
        }
    }

    /** Returns true only if this is our provider, including a handled launch error. */
    public static boolean route(Object provider, Object callback, Object device, String returnApp) {
        try {
            Class<?> cls = Class.forName(API + "ServiceProviderApp");
            if (!TARGET.equals(cls.getMethod("g").invoke(provider))
                    || !"netease360ra".equals(cls.getMethod("h").invoke(provider))) return false;
            Class<?> callbackType = Class.forName(API + "IaController$k");
            String model = device == null ? "" : String.valueOf(device.getClass().getMethod("getDeviceName").invoke(device));
            String url = "netease360ra://immersive-audio.sony.com/start-optimize?hrtf="
                    + encode(PROFILE_URI) + "&dev=" + encode(model) + "&app=" + encode(returnApp == null ? "" : returnApp);
            Object launched = callbackType.getMethod("b", String.class).invoke(callback, url);
            if (!Boolean.TRUE.equals(launched)) {
                callbackType.getMethod("a", String.class, String.class).invoke(callback,
                        "BridgeLaunchError", "无法打开网易云耳形设置页面；两端均须安装配套 R1 安装包。");
            }
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sound Connect bridge API mismatch", e);
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
}
