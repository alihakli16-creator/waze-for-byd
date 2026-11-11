// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.os.Build;

/** Local Support status for the cluster map and HUD; never part of diagnostics reports. */
public final class ClusterStatus {
    static volatile String map = "Gösterge hostu çalışmıyor", surface = "yok", displays = "", cluster,
            theme = "Gösterge haritası: henüz gösterilmedi.", hud = "HUD aktarımı: Alıcı kontrol ediliyor.", wazeCarTheme = "", wazeMainTheme = "";
    static volatile boolean untestedHudAvailable, standaloneActive;
    public static String theme() {
        return theme + (wazeCarTheme.isEmpty() ? "" : " " + wazeCarTheme) + (wazeMainTheme.isEmpty() ? "" : " " + wazeMainTheme);
    }
    public static String hud() { return hud; }
    public static boolean untestedHudAvailable() { return untestedHudAvailable; }
    public static boolean standaloneActive() { return standaloneActive; }
    private ClusterStatus() { }

    public static String describe(Context context) {
        ClusterSettings.State settings = ClusterSettings.state(context);
        boolean verified = DiLink51Layout.verified(Build.FINGERPRINT);
        boolean active = DiLink51Layout.active(settings.profile, Build.FINGERPRINT);
        StringBuilder s = new StringBuilder("Yazılım: ").append(Build.FINGERPRINT)
                .append("\nGösterge düzeni: ").append(active ? (verified ? "DiLink 5.1, doğrulanmış yazılım" : "DiLink 5.1, doğrulanmamış yazılım için seçildi")
                        : "DiLink 5" + (verified ? ", manuel seçildi" : ""));
        if (active) s.append("\nKullanım Erişimi: ").append(ClusterActivityMonitor.hasAccess(context) ? "verildi" : "verilmedi");
        String stock = cluster;
        if (stock != null) s.append("\nOrijinal gösterge: ").append(stock);
        s.append("\nGöstergede harita: ").append(settings.mapEnabled ? "açık" : "kapalı").append(", yönlendirme: ").append(settings.vehicleGuidance ? "açık" : "kapalı")
                .append("\n").append(theme())
                .append("\nHarita: ").append(map).append("\nWaze katmanı: ").append(surface)
                .append("\nEkranlar: ").append(displays.isEmpty() ? "bilinmiyor" : displays);
        if (DiLink3Projection.isCandidate() || settings.profile == DiLink51Layout.Profile.DILINK3) {
            s.append("\nDiLink 3 yansıtma: ").append(settings.dilink3Enabled ? "etkin" : "kapalı")
                    .append("\nDiLink 3 durumu: ").append(DiLink3Projection.status())
                    .append("\nDiLink 3 tanılaması: ").append(DiLink3Projection.diLink3Diagnostics());
        }
        return s.append("\n").append(hud).append("\n").append(StandaloneHudGate.describe(context)).toString();
    }
}
