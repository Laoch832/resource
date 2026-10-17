import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/** Independent programmatic verification: does not trust a CLI exit status. */
public final class VerifySignature {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Expected at least one APK");
        byte[] first = null;
        for (String path : args) {
            ApkVerifier.Result result = new ApkVerifier.Builder(new File(path)).build().verify();
            if (!result.isVerified()) throw new IllegalStateException("Invalid APK signature: " + path + ": " + result.getErrors());
            if (result.getSignerCertificates().size() != 1) throw new IllegalStateException("Expected one signer");
            X509Certificate certificate = result.getSignerCertificates().get(0);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            if (first == null) first = hash;
            else if (!Arrays.equals(first, hash)) throw new IllegalStateException("Bridge apps must use the same signature");
            StringBuilder fingerprint = new StringBuilder();
            for (byte value : hash) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            System.out.println(new File(path).getName() + ": VERIFIED; V1=" + result.isVerifiedUsingV1Scheme()
                    + "; V2=" + result.isVerifiedUsingV2Scheme() + "; V3=" + result.isVerifiedUsingV3Scheme()
                    + "; certificate SHA-256=" + fingerprint);
        }
        System.out.println("PASS: APK signatures valid and use the same certificate. This check does not test installation.");
    }
}
