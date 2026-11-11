package android.os;

/**
 * Minimal test stub for android.os.Build.
 * SDK_INT is non-final to allow testing different Android versions (e.g. Android 10 vs modern phones).
 */
public class Build {
    public static final String FINGERPRINT = "BYD/DiLink3/generic:10/QD1A.190825.001:user/release-keys";
    public static class VERSION {
        public static int SDK_INT = 29;
    }
}
