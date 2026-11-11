// SPDX-License-Identifier: GPL-3.0-only
// Ported from alihakli16-creator/maps-automotive-for-byd-patches (runtime/local/maps/cluster/DiLink3Projection.java)
package local.waze.cluster;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Display;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit DiLink 3 map-only projection manager for Waze on BYD vehicles.
 * Manages virtual display creation, AutoContainer parcel commands, crash quarantine journal,
 * and 30-second healthy auto-resume across quickboot cycles.
 */
public final class DiLink3Projection {
    public static final String DISPLAY_NAME = "fission_bg_xdjaVirtualSurface";
    private static final String PREFS = "local_dilink3_projection", OWN = "com.waze";
    private static final String JOURNAL = "waze-dilink3-projection-recovery";
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WazeDiLink3Projection");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong generation = new AtomicLong(), settingsGeneration = new AtomicLong();
    private static final Object resumeLock = new Object();
    private static final AtomicBoolean queued = new AtomicBoolean();
    private static volatile Application context;
    private static volatile Runnable changed;
    private static volatile boolean loaded, optedIn, saving, wanted, ready, visible, pending, paused;
    private static volatile boolean quarantined, quarantineDurable;
    private static volatile String state = "DiLink 3 yansıtması yükleniyor… / Loading DiLink 3 projection…";
    private static volatile VehicleLocalAdb activeConnection;
    private static volatile long nextAttempt;
    private static volatile int attempts = 3;
    private static long projectionGeneration = -1;
    private static final String TAG = "WazeDiLink3";

    /**
     * Automatic resume after a car power cycle (BYD quickboot force-stops apps mid-projection).
     * Durable auto_resume_ok is true only after a projection window ran HEALTHY_MS without a failure.
     */
    static final long HEALTHY_MS = 30000;
    static long healthyMs = HEALTHY_MS;
    private static volatile boolean autoResumeOk, failedThisProcess, autoResumeReported;
    private static final AtomicBoolean autoResumeUsed = new AtomicBoolean();
    private static volatile boolean ownedByWaze;
    private static volatile String lastSeenBuild = "bilinmiyor / unknown", lastSeenProduct = "bilinmiyor / unknown", lastSeenAdapter = "bilinmiyor / unknown";

    private DiLink3Projection() {}

    /** UI eligibility only. Runtime service/adapter/display checks are required before every write. */
    public static boolean isCandidate() { return Build.VERSION.SDK_INT == 29; }
    public static boolean loaded() { return isCandidate() && loaded; }
    public static boolean enabled() { return isCandidate() && loaded && !saving && optedIn; }
    public static boolean ready() { return enabled() && wanted && !paused && !quarantined && ready; }
    public static boolean recoveryPending() { return isCandidate() && pending && !ready(); }

    public static String candidateRejectionReason() {
        if (Build.VERSION.SDK_INT != 29) {
            return "Android 10 (SDK 29) gerekli; mevcut sistem: Android SDK " + Build.VERSION.SDK_INT
                    + " / Android 10 (SDK 29) required; current: SDK " + Build.VERSION.SDK_INT;
        }
        return null;
    }

    public static String status() {
        if (!isCandidate()) {
            return candidateRejectionReason();
        }
        return state;
    }

    public static String diLink3Diagnostics() {
        return "SDK=" + Build.VERSION.SDK_INT + ", Candidate=" + isCandidate()
                + ", State=" + state + ", Build=" + lastSeenBuild + ", Product=" + lastSeenProduct
                + ", Adapter=" + lastSeenAdapter;
    }

    public static void init(Application application, Runnable onChanged) {
        if (application == null || !isCandidate()) return;
        synchronized (DiLink3Projection.class) {
            if (onChanged != null) changed = onChanged;
            if (context != null) return;
            context = application;
            worker.execute(() -> {
                boolean saved = false, resume = false, unknown = false, autoOk = false;
                try {
                    saved = preferences().getBoolean("enabled", false);
                    resume = preferences().getBoolean("resume_required", false);
                    autoOk = preferences().getBoolean("auto_resume_ok", false);
                } catch (RuntimeException unavailable) {
                    resume = true; unknown = true; autoOk = false;
                }
                autoResumeOk = autoOk;
                pending = readJournal();
                quarantined = pending || resume;
                quarantineDurable = resume && !unknown;
                optedIn = saved;
                loaded = true;
                state = quarantined ? resumeStatus()
                        : saved ? "DiLink 3 yansıtması etkin; harita isteği bekleniyor. / DiLink 3 enabled; waiting for map request."
                        : "DiLink 3 yansıtması kapalı. / DiLink 3 projection is off.";
                notifyChanged();
                requestWork(true);
            });
            worker.scheduleWithFixedDelay(() -> {
                if (loaded && attempts > 0 && SystemClock.uptimeMillis() >= nextAttempt
                        && (pending && !ready() || enabled() && wanted && !paused && !quarantined && !ready())) {
                    requestWork(false);
                }
            }, 30, 30, TimeUnit.SECONDS);
        }
    }

    private static SharedPreferences preferences() { return context.getSharedPreferences(PREFS, 0); }
    private static void notifyChanged() { ui.post(() -> { Runnable callback = changed; if (callback != null) callback.run(); }); }
    private static void cancelledConnection() { VehicleLocalAdb connection = activeConnection; if (connection != null) connection.close(); }
    private static void invalidateState() { generation.incrementAndGet(); ready = false; visible = false; }
    private static void invalidate() { synchronized (resumeLock) { invalidateState(); } cancelledConnection(); }

    public static void configure(Activity activity, boolean on, boolean allowKey, Runnable done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isCandidate()) { finish(done); return; }
        init(activity.getApplication(), null);
        if (!loaded) { finish(done); return; }
        final long saveEpoch = settingsGeneration.incrementAndGet();
        optedIn = on; saving = true; paused = false; invalidate();
        final long projectionEpoch = generation.get(); attempts = 3; nextAttempt = 0;
        state = on ? "DiLink 3 yansıtma tercihi kaydediliyor… / Saving DiLink 3 preference…"
                   : "Yansıtma kapatılıyor ve orijinal gösterge modu geri yükleniyor… / Restoring stock cluster…";
        notifyChanged();
        worker.execute(() -> {
            try {
                if (settingsGeneration.get() != saveEpoch) return;
                boolean saved;
                try { saved = preferences().edit().putBoolean("enabled", on).commit(); } catch (RuntimeException unavailable) { saved = false; }
                if (settingsGeneration.get() != saveEpoch) return;
                saving = false;
                if (!saved) { optedIn = false; state = "Tercih kaydedilemedi; tekrar deneyin. / Could not save preference; retry."; }
                runLatest(projectionEpoch, on && allowKey && saved, true, on && saved);
            } finally { finish(done); }
        });
    }

    public static void retry(Activity activity, boolean allowKey, Runnable done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isCandidate() || !loaded) { finish(done); return; }
        paused = false; invalidate(); attempts = 3; nextAttempt = 0; final long epoch = generation.get();
        worker.execute(() -> { try { runLatest(epoch, allowKey, true, true); } finally { finish(done); } });
    }

    /**
     * Authorize ADB connection only without altering cluster projection or AutoContainer state.
     * Triggers RSA key approval prompt if not yet authorized, runs preflight, and records device details.
     */
    public static void authorizeOnly(Activity activity, Runnable done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isCandidate()) {
            finish(done);
            return;
        }
        init(activity.getApplication(), null);
        state = "ADB bağlantısı kuruluyor ve RSA anahtarı talep ediliyor… / Connecting ADB and requesting RSA key…";
        notifyChanged();
        worker.execute(() -> {
            try {
                try (VehicleLocalAdb adb = VehicleLocalAdb.open(context.getNoBackupFilesDir(), true, () -> true)) {
                    preflight(adb);
                    state = "ADB yetkilendirildi! Ön kontrol başarılı.\nCihaz: " + lastSeenBuild + " / " + lastSeenProduct
                            + "\nAMap Servisi: " + lastSeenAdapter
                            + "\nADB authorized! Preflight OK. Ready for projection.";
                } catch (VehicleLocalAdb.ApprovalRequiredException e) {
                    state = "Waze ADB anahtarını ekrandan onaylayın ('Her zaman izin ver'), ardından tekrar deneyin.\nApprove Waze ADB RSA key ('Always allow'), then retry.";
                } catch (VehicleLocalAdb.ConnectionRefusedException e) {
                    state = e.getMessage();
                } catch (Failure f) {
                    state = "ADB ön kontrol başarısız: " + failureStatus(f.code);
                } catch (Exception e) {
                    state = "ADB bağlantı hatası (" + e.getClass().getSimpleName() + "). Ağ ADB'yi kontrol edin.\nADB connection failed. Check network ADB.";
                }
                notifyChanged();
            } finally {
                finish(done);
            }
        });
    }

    /**
     * Normal graceful restore. Only restores if Waze owns the projection or journal.
     */
    public static void restore(Activity activity, Runnable done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isCandidate() || !loaded) { finish(done); return; }
        paused = true; invalidate(); attempts = 3; nextAttempt = 0; final long epoch = generation.get();
        worker.execute(() -> { try { saveAutoOk(false); runLatest(epoch, false, true); } finally { finish(done); } });
    }

    /**
     * Force restore triggered explicitly by user from the settings panel.
     * Restores stock cluster via AutoContainer parcel commands 18 and 35 regardless of prior session ownership.
     */
    public static void forceRestore(Activity activity, Runnable done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isCandidate()) { finish(done); return; }
        init(activity.getApplication(), null);
        paused = true; invalidate(); attempts = 3; nextAttempt = 0;
        state = "Orijinal gösterge modu zorla geri yükleniyor… / Force restoring stock cluster…";
        notifyChanged();
        worker.execute(() -> {
            try {
                saveAutoOk(false);
                try (VehicleLocalAdb adb = VehicleLocalAdb.open(context.getNoBackupFilesDir(), true, () -> true)) {
                    String user = preflight(adb);
                    send(adb, 18, user);
                    send(adb, 35, user);
                    saveJournal(false);
                    saveQuarantine(false);
                    quarantined = false;
                    quarantineDurable = false;
                    ownedByWaze = false;
                    projectionGeneration = -1;
                    state = "Orijinal gösterge modu geri yüklendi. / Stock cluster restored.";
                } catch (Exception e) {
                    state = "Geri yükleme başarısız (" + e.getClass().getSimpleName() + "). ADB'yi kontrol edin.\nRestore failed. Check ADB connection.";
                }
                notifyChanged();
            } finally { finish(done); }
        });
    }

    private static void finish(Runnable done) { if (done != null) ui.post(done); }

    public static void reconcile(boolean mapWanted) {
        if (!isCandidate() || context == null) return;
        if (wanted == mapWanted) return;
        wanted = mapWanted; paused = false; invalidate(); attempts = 3; nextAttempt = 0; requestWork(true);
    }

    public static void windowVisible(boolean shown) {
        if (!isCandidate() || !loaded) return;
        if (shown) {
            if (ready()) {
                boolean first = !visible;
                visible = true;
                state = "Yansıtma komutları ve harita penceresi hazır. Fiziksel göstergeyi kontrol edin. / Projection ready. Check physical cluster.";
                if (first) confirmHealthyLater();
            }
            return;
        }
        if (!ready && !visible) return;
        windowFailed();
    }

    public static void windowFailed() {
        if (!isCandidate() || context == null) return;
        synchronized (resumeLock) { quarantined = true; quarantineDurable = false; paused = wanted; failedThisProcess = true; invalidateState(); }
        cancelledConnection(); state = resumeStatus(); attempts = 3; nextAttempt = 0; requestWork(true);
    }

    private static void confirmHealthyLater() {
        final long epoch = generation.get(), owned = projectionGeneration;
        worker.schedule(() -> {
            if (!ready() || !visible || failedThisProcess || generation.get() != epoch || projectionGeneration != owned) return;
            if (saveAutoOk(true)) Log.i(TAG, "projection healthy for " + healthyMs + " ms; automatic resume allowed after next power cycle");
        }, healthyMs, TimeUnit.MILLISECONDS);
    }

    private static boolean saveAutoOk(boolean value) {
        boolean saved;
        try { saved = preferences().edit().putBoolean("auto_resume_ok", value).commit(); } catch (RuntimeException unavailable) { saved = false; }
        if (saved || !value) autoResumeOk = saved ? value : autoResumeOk && value;
        return saved;
    }

    private static boolean autoResumeEligible(long epoch) {
        return autoResumeOk && !failedThisProcess && quarantined && !pending && !autoResumeUsed.get() && projectionAllowedAfterResume(epoch);
    }

    private static boolean projectionAllowedAfterResume(long epoch) { return allowed(epoch) && optedIn && wanted && !paused; }

    private static String resumeStatus() {
        return pending ? "Önceki yansıtma oturumu orijinal gösterge düzenine dönülmesini gerektiriyor. Yansıtma duraklatıldı. Devam etmek için Yetkilendir / Tekrar Dene butonuna basın.\nPrevious projection session requires stock cluster restoration. Press Authorize / Retry to resume."
                       : "Gösterge yansıtması önceki oturumdan sonra onay gerektiriyor. Yansıtma duraklatıldı; devam etmek için Yetkilendir / Tekrar Dene butonuna basın.\nProjection paused; press Authorize / Retry to resume.";
    }

    private static boolean saveQuarantine(boolean value) {
        try { return preferences().edit().putBoolean("resume_required", value).commit(); } catch (RuntimeException unavailable) { return false; }
    }

    public static boolean matches(Display display) {
        try {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY || !display.isValid()
                    || !DISPLAY_NAME.equals(display.getName())
                    || (display.getFlags() & Display.FLAG_PRIVATE) != 0
                    || (display.getFlags() & Display.FLAG_PRESENTATION) == 0) return false;
            Point size = new Point(); display.getRealSize(size);
            return size.x == 1920 && size.y == 720 || size.x == 1280 && size.y == 480;
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean displayPresent() throws IOException {
        DisplayManager manager = context.getSystemService(DisplayManager.class); if (manager == null) return false;
        int matches = 0; for (Display display : manager.getDisplays()) if (matches(display) && ++matches > 1) throw new Failure("display_ambiguous");
        return matches == 1;
    }

    private static boolean allowed(long epoch) { return context != null && isCandidate() && loaded && generation.get() == epoch && !saving; }
    private static boolean projectionWanted(long epoch) { return allowed(epoch) && optedIn && wanted && !paused && !quarantined; }
    private static void check(long epoch) throws IOException { if (!projectionWanted(epoch)) throw new Failure("cancelled"); }

    private static void requestWork(boolean force) {
        if (context == null || !loaded || !isCandidate() || !force && (attempts <= 0 || SystemClock.uptimeMillis() < nextAttempt)) return;
        if (!queued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            long epoch = generation.get();
            try { runLatest(epoch, false, force); } finally {
                queued.set(false);
                if (generation.get() != epoch && (pending || enabled() && wanted)) requestWork(true);
            }
        });
    }

    private static void runLatest(long epoch, boolean allowKey, boolean force) { runLatest(epoch, allowKey, force, false); }

    private static void runLatest(long epoch, boolean allowKey, boolean force, boolean manualResume) {
        if (!allowed(epoch) || !force && (attempts <= 0 || SystemClock.uptimeMillis() < nextAttempt)) return;
        if (ready && projectionGeneration == epoch && projectionWanted(epoch)) return;
        if (pending || projectionWanted(epoch)) { attempts = Math.max(0, attempts - 1); nextAttempt = SystemClock.uptimeMillis() + 30000; }
        boolean cleanupFailed = false;
        try {
            if (quarantined && !quarantineDurable) { quarantineDurable = saveQuarantine(true); if (failedThisProcess) saveAutoOk(false); }

            boolean displayExists = displayPresent();

            // Collision & Ambiguous Ownership check:
            // Case 1: If display exists and Waze has NO unclosed journal (!pending): another app owns it.
            if (displayExists && !ownedByWaze && !pending) {
                state = "Sanal gösterge ekranı zaten başka bir uygulama tarafından kullanılıyor. Göstergede aynı anda tek bir navigasyon uygulaması kullanın.\n"
                      + "Virtual cluster display is already in use by another app. Use only one cluster navigation app at a time.";
                notifyChanged();
                return;
            }

            // Case 2: Display exists AND Waze journal is pending. This is ambiguous: Waze died earlier, but another
            // app (e.g. Google Maps) might have been opened since and could currently be using the virtual display.
            // DO NOT automatically restore or tear down the screen! Wait for explicit user action via the button.
            if (displayExists && pending && !manualResume) {
                state = "Önceki Waze oturumu temiz kapanmadı; göstergeyi geri yüklemek için düğmeye basın.\n"
                      + "Previous Waze session was not cleanly closed; press button to restore stock cluster.";
                notifyChanged();
                return;
            }

            // If pending is true and manualResume is requested (or display doesn't exist), clean up the pending journal.
            if (pending && !restorePending(allowKey, epoch)) throw new Failure("restore");

            if (manualResume && quarantined && allowed(epoch) && optedIn && wanted && !paused) {
                saveJournal(true);
                if (!allowed(epoch)) return;
                saveAutoOk(false);
                if (!saveQuarantine(false)) { quarantineDurable = saveQuarantine(true); throw new Failure("resume"); }
                boolean current;
                synchronized (resumeLock) { current = allowed(epoch); if (current) { quarantined = false; quarantineDurable = false; } }
                if (!current) { quarantineDurable = saveQuarantine(true); return; }
            } else if (!manualResume && quarantined && projectionAllowedAfterResume(epoch)) {
                if (autoResumeEligible(epoch) && autoResumeUsed.compareAndSet(false, true)) {
                    saveJournal(true);
                    if (!allowed(epoch)) return;
                    if (!saveAutoOk(false)) throw new Failure("resume");
                    if (!saveQuarantine(false)) { quarantineDurable = saveQuarantine(true); throw new Failure("resume"); }
                    boolean current;
                    synchronized (resumeLock) { current = allowed(epoch) && !failedThisProcess; if (current) { quarantined = false; quarantineDurable = false; } }
                    if (!current) { quarantineDurable = saveQuarantine(true); return; }
                    Log.i(TAG, "autoResume once after previous session: resuming projection");
                } else if (!autoResumeReported) {
                    autoResumeReported = true;
                    Log.i(TAG, "autoResume skipped: " + (autoResumeUsed.get() ? "already used in this process" : failedThisProcess ? "projection failed in this process" : !autoResumeOk ? "previous attempt not confirmed healthy" : "stock restoration pending"));
                }
            }

            if (!quarantined && projectionWanted(epoch) && !ready) saveAutoOk(false);

            if (!projectionWanted(epoch)) {
                if (allowed(epoch)) {
                    state = quarantined ? resumeStatus()
                            : paused ? "Orijinal gösterge modu geri yüklendi. Yeniden yansıtmak için Yetkilendir / Tekrar Dene butonuna basın.\nStock cluster restored. Press Authorize / Retry to resume."
                            : "DiLink 3 yansıtması kapalı; orijinal gösterge modu geri yüklendi.\nDiLink 3 projection is off; stock cluster restored.";
                }
                notifyChanged();
                return;
            }

            try (VehicleLocalAdb adb = VehicleLocalAdb.open(context.getNoBackupFilesDir(), allowKey, () -> projectionWanted(epoch))) {
                activeConnection = adb;
                String user = preflight(adb);
                check(epoch);
                if (!displayPresent()) {
                    state = "Eksik DiLink 3 yansıtma ekranı oluşturuluyor… / Spawning DiLink 3 virtual display…";
                    notifyChanged();
                    saveJournal(true);
                    check(epoch);
                    send(adb, 16, user);
                    grace(epoch, 30);
                    send(adb, 35, user);
                    grace(epoch, 30);
                    if (!restorePending(false, epoch)) throw new Failure("restore");
                    check(epoch);
                    if (!displayPresent()) throw new Failure("display");
                }
                check(epoch);
                state = "DiLink 3 yansıtmasına giriliyor… / Entering DiLink 3 projection…";
                notifyChanged();
                saveJournal(true);
                check(epoch);
                send(adb, 16, user);
                grace(epoch, 10);
                send(adb, 17, user);
                check(epoch);
                if (!displayPresent()) throw new Failure("display");
                ownedByWaze = true;
                projectionGeneration = epoch;
                ready = true;
                visible = false;
                state = "Yansıtma komutları kabul edildi; harita penceresi bekleniyor. / Projection commands accepted; waiting for map window.";
                notifyChanged();
            } finally { activeConnection = null; }
        } catch (Exception failure) {
            ready = false; visible = false;
            if (pending && ownedByWaze) {
                try { cleanupFailed = !restorePending(false, epoch); } catch (Exception unavailable) { cleanupFailed = true; }
            }
            if (generation.get() == epoch) {
                nextAttempt = SystemClock.uptimeMillis() + 30000;
                if (cleanupFailed || pending) {
                    state = "Orijinal göstergeye geri dönülüyor. Yeni yansıtma engellendi; ADB'yi kontrol edip Tekrar Dene'ye basın.\nRestoring stock cluster. Check ADB and press Retry.";
                } else if (failure instanceof VehicleLocalAdb.ApprovalRequiredException) {
                    state = "Waze ADB anahtarını onaylayın ('Her zaman izin ver'), ardından Tekrar Dene'ye basın.\nApprove Waze ADB RSA key ('Always allow'), then press Retry.";
                } else if (failure instanceof VehicleLocalAdb.ConnectionRefusedException) {
                    state = failure.getMessage();
                } else if (failure instanceof Failure) {
                    state = failureStatus(((Failure) failure).code);
                } else {
                    state = "DiLink 3 yansıtması tamamlanamadı (" + failure.getClass().getSimpleName() + "). ADB'yi kontrol edip Tekrar Dene'ye basın.\nDiLink 3 projection failed. Check ADB and press Retry.";
                }
                notifyChanged();
            }
        }
    }

    private static String currentUser(VehicleLocalAdb adb) throws IOException {
        String value = adb.execute("am get-current-user").trim();
        if (!value.matches("[0-9]{1,6}")) throw new Failure("user");
        return Integer.toString(Integer.parseInt(value));
    }

    private static String preflight(VehicleLocalAdb adb) throws IOException {
        if (!isCandidate() || !OWN.equals(context.getPackageName())) throw new Failure("identity");
        if (!"2000".equals(adb.execute("id -u").trim())) throw new Failure("shell");
        int uid = Process.myUid(); String user = currentUser(adb);
        if (uid < 0 || !Integer.toString(uid / 100000).equals(user)) throw new Failure("user");
        if (!"29".equals(adb.execute("getprop ro.build.version.sdk").trim())) throw new Failure("sdk");
        String build = adb.execute("getprop ro.build.display.id"), product = adb.execute("getprop ro.product.name");
        lastSeenBuild = sanitize(build, 64);
        lastSeenProduct = sanitize(product, 64);
        if (!diLink3Name(build) && !diLink3Name(product)) {
            Log.w(TAG, "preflight build mismatch: build=" + lastSeenBuild + " product=" + lastSeenProduct);
            throw new Failure("build");
        }
        if (!"Service AutoContainer: found".equals(adb.execute("service check AutoContainer").trim())) throw new Failure("service");

        // Check AMap packages without blocking projection if missing
        boolean hasExample = false, hasByd = false;
        try {
            String pExample = adb.execute("pm path --user " + user + " com.example.amapservice").trim();
            hasExample = pExample.startsWith("package:") && pExample.contains(".apk");
        } catch (Exception ignored) {}
        try {
            String pByd = adb.execute("pm path --user " + user + " com.byd.amapservice").trim();
            hasByd = pByd.startsWith("package:") && pByd.contains(".apk");
        } catch (Exception ignored) {}

        if (hasExample && hasByd) lastSeenAdapter = "com.example.amapservice ve com.byd.amapservice mevcut";
        else if (hasExample) lastSeenAdapter = "com.example.amapservice mevcut";
        else if (hasByd) lastSeenAdapter = "com.byd.amapservice mevcut";
        else {
            lastSeenAdapter = "bulunamadı / not found";
            Log.w(TAG, "Neither com.example.amapservice nor com.byd.amapservice found; turn guidance may be inactive");
        }

        if (!user.equals(currentUser(adb))) throw new Failure("user");
        return user;
    }

    /** Sanitizes string for display: strips control characters and truncates to maxLen. */
    static String sanitize(String input, int maxLen) {
        if (input == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length() && sb.length() < maxLen; i++) {
            char c = input.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String res = sb.toString().trim();
        return res.isEmpty() ? "empty" : res;
    }

    /** Positive system-name evidence, not a generic Android 10/version-number guess. */
    static boolean diLink3Name(String input) {
        if (input == null || input.length() > 256) return false;
        String value = input.trim();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return Pattern.compile("(?i)(?<![a-z0-9])DiLink3(?:\\.0)?(?![a-z0-9.])").matcher(value).find();
    }

    private static void send(VehicleLocalAdb adb, int command, String user) throws IOException {
        if (command != 16 && command != 17 && command != 18 && command != 35) throw new Failure("command");
        if (!user.equals(currentUser(adb))) throw new Failure("user");
        if (!accepted(adb.execute("service call AutoContainer 2 i32 1000 i32 " + command + " s16 ''"))) throw new Failure("parcel");
    }

    static boolean accepted(String output) {
        if (output == null || output.length() > 4096) return false;
        Matcher match = Pattern.compile("(?s)^(?:Result:\\s*)?Parcel\\(\\s*(?:0x[0-9a-fA-F]{8}:\\s*)?([0-9a-fA-F]{8})(?:\\s.*)?\\)\\s*$").matcher(output.trim());
        return match.matches() && "00000000".equals(match.group(1));
    }

    private static void grace(long epoch, int steps) throws IOException {
        for (int i = 0; i < steps; i++) {
            check(epoch);
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure("cancelled"); }
        }
        check(epoch);
    }

    private static AtomicFile journal() { return new AtomicFile(new File(context.getNoBackupFilesDir(), JOURNAL)); }
    private static boolean readJournal() { return !Boolean.FALSE.equals(journalValue()); }

    private static Boolean journalValue() {
        AtomicFile file = journal();
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[32]; int count;
            while ((count = input.read(buffer)) != -1) { if (bytes.size() + count > 32) return null; bytes.write(buffer, 0, count); }
            String value = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            return "pending\n".equals(value) ? Boolean.TRUE : "clean\n".equals(value) ? Boolean.FALSE : null;
        } catch (FileNotFoundException missing) {
            File base = file.getBaseFile(); return base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists() ? null : Boolean.FALSE;
        } catch (IOException | RuntimeException unreadable) { return null; }
    }

    private static void saveJournal(boolean next) throws IOException {
        AtomicFile file = journal(); FileOutputStream output = null; boolean previousPending = pending;
        try {
            output = file.startWrite(); output.write((next ? "pending\n" : "clean\n").getBytes(StandardCharsets.US_ASCII));
            output.flush(); output.getFD().sync(); file.finishWrite(output); output = null;
            Boolean confirmed = journalValue(); if (confirmed == null || confirmed.booleanValue() != next) throw new Failure("journal");
            pending = next;
        } catch (IOException | RuntimeException failure) {
            try { if (output != null) file.failWrite(output); } catch (RuntimeException ignored) {}
            pending = previousPending || Boolean.TRUE.equals(journalValue());
            throw new Failure("journal");
        }
    }

    private static boolean restorePending(boolean allowKey, long epoch) throws IOException {
        if (!pending) return true;
        ready = false; visible = false;
        try (VehicleLocalAdb adb = VehicleLocalAdb.open(context.getNoBackupFilesDir(), allowKey, () -> context != null && isCandidate() && (!allowKey || generation.get() == epoch))) {
            String user = preflight(adb);
            send(adb, 18, user);
            if (!saveQuarantine(true)) throw new Failure("resume");
            quarantineDurable = true;
            saveJournal(false);
            projectionGeneration = -1;
            ownedByWaze = false;
            return true;
        }
    }

    private static final class Failure extends IOException { final String code; Failure(String code) { this.code = code; } }

    private static String failureStatus(String code) {
        if ("identity".equals(code) || "sdk".equals(code)) return "Android/paket kimliği DiLink 3 ile eşleşmiyor (SDK 29 ve com.waze gerekli).\nAndroid/package identity mismatch.";
        if ("build".equals(code)) return "Sistem sürümü DiLink 3 olarak tanımlanamadı (build=" + lastSeenBuild + ", product=" + lastSeenProduct + ").\nFirmware build not recognized as DiLink 3.";
        if ("shell".equals(code)) return "Yerel ADB kabuğu (shell uid 2000) doğrulanamadı.\nLocal ADB shell identity unverified.";
        if ("user".equals(code)) return "Mevcut Android kullanıcısı değişti veya doğrulanamadı.\nAndroid user profile mismatch.";
        if ("service".equals(code)) return "Gerekli AutoContainer sistem servisi bulunamadı.\nAutoContainer system service not found.";
        if ("adapter".equals(code)) return "DiLink 3 harita bağdaştırıcısı (com.example.amapservice) bulunamadı.\nAMap DiLink 3 adapter not installed.";
        if ("journal".equals(code)) return "Kurtarma kaydı güvenilir şekilde kaydedilemedi.\nRecovery journal write failed.";
        if ("resume".equals(code)) return "Yansıtma güvenlik duraklatması kaydedilemedi. Yetkilendir / Tekrar Dene'ye basın.\nQuarantine pause latch failed. Press Authorize / Retry.";
        if ("display".equals(code)) return "DiLink 3 yansıtma ekranı (fission_bg_xdjaVirtualSurface) kullanılamıyor. Tekrar Dene'ye basın.\nVirtual display unavailable. Press Retry.";
        if ("display_ambiguous".equals(code)) return "Birden fazla eşleşen DiLink 3 yansıtma ekranı mevcut.\nMultiple matching virtual displays found.";
        if ("parcel".equals(code)) return "AutoContainer kabul edilen bir yanıt döndürmedi (00000000 bekleniyordu).\nAutoContainer rejected parcel call.";
        if ("cancelled".equals(code)) return "Yansıtma isteği değişti; mod araca geri devredildi.\nProjection request cancelled.";
        return "DiLink 3 yansıtması tamamlanamadı (" + code + "). ADB'yi kontrol edip Tekrar Dene'ye basın.\nProjection error. Check ADB and press Retry.";
    }
}
