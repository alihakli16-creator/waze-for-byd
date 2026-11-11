package android.os;

/**
 * Minimal test stub for android.os.Looper to prevent Stub! exception on JVM test runs.
 */
public class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
}
