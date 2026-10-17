import dev.soundconnect.bridge.ProfileInfo;
import dev.soundconnect.bridge.SourceBridge;
import com.sony.songpal.earcapture.j2objc.immersiveaudio.IaController;
import com.sony.songpal.earcapture.j2objc.immersiveaudio.ServiceProviderApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BridgeTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static final class Device { public String getDeviceName() { return "WH TEST / 耳机"; } }
    public static final class Callback implements IaController.k {
        String url, error; boolean launch = true; int calls;
        public void a(String code, String message) { error = code; }
        public boolean b(String value) { calls++; url = value; return launch; }
    }
    public static void main(String[] args) throws Exception {
        check(ProfileInfo.sha256("abc".getBytes("UTF-8")).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "known digest");
        byte[] bytes = new byte[]{1, 2, 3};
        check(java.util.Arrays.equals(bytes, ProfileInfo.read(new ByteArrayInputStream(bytes))), "preserve opaque profile bytes");
        try { ProfileInfo.read(new ByteArrayInputStream(new byte[0])); throw new AssertionError("accepted empty profile"); }
        catch (IOException expected) { checks++; }
        check(ProfileInfo.read(new ByteArrayInputStream(new byte[ProfileInfo.MAX_BYTES])).length == ProfileInfo.MAX_BYTES, "exact import limit");
        try { ProfileInfo.read(new ByteArrayInputStream(new byte[ProfileInfo.MAX_BYTES + 1])); throw new AssertionError("accepted oversized profile"); }
        catch (IOException expected) { checks++; }
        check(ProfileInfo.container("RIFFxxxxWAVE".getBytes("US-ASCII")).contains("未解析"), "WAVE container is not claimed rendered");
        check(ProfileInfo.container("RIFFxxxxNOPE".getBytes("US-ASCII")).contains("未确定"), "reject false WAVE magic");
        check(ProfileInfo.container(new byte[]{(byte) 137, 'H', 'D', 'F', 13, 10, 26, 10}).contains("HDF5"), "complete HDF5 signature");
        check(ProfileInfo.container(new byte[]{(byte) 137, 'H', 'D', 'F', 0, 0, 0, 0}).contains("未确定"), "reject incomplete HDF5 signature");
        List<ServiceProviderApp> providers = new ArrayList<ServiceProviderApp>();
        ServiceProviderApp original = new ServiceProviderApp("Original", "other.app", "", "other", "", "", ServiceProviderApp.LaunchType.URL_SCHEME, Collections.emptyMap());
        providers.add(original);
        SourceBridge.addProvider(providers);
        SourceBridge.addProvider(providers);
        check(providers.size() == 2 && providers.get(0) == original, "preserve services, no duplicates");
        check(providers.get(1).g().equals(SourceBridge.TARGET), "correct target package");
        Callback callback = new Callback();
        check(!SourceBridge.route(original, callback, new Device(), "sony"), "other service falls through");
        check(callback.calls == 0, "other service is untouched");
        ServiceProviderApp officialTarget = new ServiceProviderApp("Future official NetEase", SourceBridge.TARGET, "", "other-official-scheme", "", "", ServiceProviderApp.LaunchType.URL_SCHEME, Collections.emptyMap());
        check(!SourceBridge.route(officialTarget, callback, new Device(), "sony"), "preserve a separate official NetEase integration");
        check(callback.calls == 0, "only experimental scheme is intercepted");
        check(SourceBridge.route(providers.get(1), callback, new Device(), "sony app"), "target service handled");
        java.net.URI uri = new java.net.URI(callback.url);
        check(uri.getScheme().equals("netease360ra") && uri.getHost().equals("immersive-audio.sony.com"), "route destination");
        check(callback.url.contains("hrtf=content%3A%2F%2Fcom.sony.songpal.mdr.netease360ra%2Fhrtf"), "profile stays local");
        check(callback.url.contains("dev=WH+TEST+%2F+%E8%80%B3%E6%9C%BA") && callback.url.contains("app=sony+app"), "URL parameters encoded");
        check(callback.error == null, "successful launch has no error");
        callback.launch = false;
        SourceBridge.route(providers.get(1), callback, null, null);
        check("BridgeLaunchError".equals(callback.error), "launch failure surfaced");
        System.out.println("PASS: " + checks + " checks (host-side transport tests; no Android/audio runtime test)");
    }
}
