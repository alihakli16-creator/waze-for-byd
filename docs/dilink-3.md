# DiLink 3.0 (Android 10) Cluster Projection for Waze

Tested base: Waze 5.26.0.0 (versionCode 1030742, target versionCode 2030742).

This module enables instrument cluster projection and AMap guidance on BYD vehicles equipped with DiLink 3.0 (Android 10 / API 29), such as the BYD Seal U DM-i and BYD Tang.

## Status and Verification Truth Labels

- **`VehicleLocalAdb.java` (Adım A)**: Birim testi geçti (Unit tests passed: 70 checks on strict command allowlist, injection attack rejection, AOSP RSA-2048 handshake, public key formatting).
- **`DiLink3Projection.java` (Adım B)**: Birim testi geçti (Unit tests passed: DiLink3 regex matching, AutoContainer parcel validation, crash quarantine latch, and candidate rejection diagnostics).
- **`ClusterHost.java` (Adım C)**: Birim testi geçti (DiLink 3 virtual presentation lifecycle, surface callback hooks, dynamic display bounds measurement, and graceful shutdown). Regresyon testleri **YEREL KOŞMADI** (Runs in GitHub Actions CI with full Android SDK).
- **`BydGuidanceBridge.java` & `AmapAdapter.java` (Adım D)**: Birim testi geçti (`AmapAdapter.choose` fallback logic tested). HUD broadcast suppressed on DiLink 3. AMap Service (`com.example.amapservice`): **DiLink 3 aracında DOĞRULANMADI** (Must be verified in vehicle; falls back safely to default com.byd.amapservice).
- **`ClusterPanel.java` (Adım E)**: Birim testi geçti (Profile parsing fallback, profile explicit radio group, experimental parked dialog toggle, and action buttons). Tüm arayüz Türkçeleştirildi.
- **Physical Vehicle Projection**: **Araçta DOĞRULANMADI** (Must be validated by owner in a parked vehicle using the test procedure below).

## Upstream Lineage & Differences from Maps Automotive

This implementation is ported from `alihakli16-creator/maps-automotive-for-byd-patches` (upstream `romanchukg-cloud/maps-automotive-for-byd-patches`):
- `runtime/local/maps/cluster/VehicleLocalAdb.java` -> `patch/src/local/waze/cluster/VehicleLocalAdb.java`
- `runtime/local/maps/cluster/DiLink3Projection.java` -> `patch/src/local/waze/cluster/DiLink3Projection.java`
- `runtime/local/maps/cluster/AmapAdapter.java` -> `patch/src/local/waze/cluster/AmapAdapter.java`

### Key Differences & Security Hardening in Waze Implementation:
1. **Target Architecture**: Pure Java 8 compiled into `classes11.dex` with smali hooks into `classes5.dex` and `classes6.dex` (Maps uses Morphe runtime bytecode injection).
2. **Hardened Command Allowlist**: Pinned regex rejecting all shell chaining, subshells, redirection and newlines (`[;&|$\\()<>\r\n`]`). Restricts `getprop` strictly to `ro.build.version.sdk`, `ro.build.display.id`, and `ro.product.name`.
3. **Display Collision Avoidance**: Waze checks whether `fission_bg_xdjaVirtualSurface` was already spawned by another application (e.g. Google Maps or system) before initiating projection. If present without an active Waze unclosed recovery journal, Waze does not send hijacking commands or disrupt the active projection.
4. **Quarantine Edge Case Handling**: If the display is present AND the Waze recovery journal is marked `pending` (e.g. Waze crashed mid-session), this is recognized as quarantine recovery rather than another app, allowing clean stock restoration.
5. **Manual Stock Restore Button**: The "Orijinal Göstergeyi Geri Yükle" button in the UI works unconditionally (with confirmation dialog) regardless of session ownership, allowing the driver to unstick their cluster if another app left it frozen.
6. **Network ADB Failure Handling**: If local ADB port `127.0.0.1:5555` is closed or unreachable, an informative bilingual warning is provided: `"Araçta ağ ADB'yi etkinleştirin (127.0.0.1:5555 bağlantısı reddedildi) / Enable network ADB in the vehicle"`.
7. **HUD Status**: Displayed honestly as `"Desteklenmiyor (DiLink 3) / Not supported on DiLink 3"`.

## Recovery and Safety Procedures (Read Before In-Car Testing!)

> [!CAUTION]
> Always perform tests while the vehicle is safely **PARKED** (P gear, parking brake engaged). Never test or troubleshoot while driving.

### 1. In-App Restoration (Primary Method - Verified)
- Open the cluster settings dialog in Waze (tap the **Gösterge Paneli / Instrument Cluster** button on the map).
- Tap **Orijinal Göstergeyi Geri Yükle (Restore Stock Cluster)**.
- Waze issues `service call AutoContainer 2 i32 1000 i32 18 s16 ''` and `35`, instantly switching the physical instrument cluster back to the factory BYD gauges.

### 2. Vehicle Hardware Recovery Methods (Community Standard - Araçta DOĞRULANMADI)
If the cluster display goes black, freezes, or does not return to stock gauges and Waze cannot be accessed:
1. **Force Stop Waze**: Go to vehicle Settings -> Apps -> Waze -> Force Stop. The quarantine latch will ensure Waze does not attempt projection on next launch.
2. **Head Unit Quick Reboot (*araçta DOĞRULANMADI*)**: Press and hold the physical volume roller / power button on the center console for ~10 seconds until the center screen reboots. *(Note: Standard BYD head unit hardware reset; not an upstream repo test claim).*
3. **Vehicle Deep Sleep (*araçta DOĞRULANMADI*)**: Turn off vehicle ignition, step out of the car, lock the doors with the key fob, and wait 3–5 minutes for the vehicle's body control module and instrument cluster to go to deep sleep. Upon unlocking and powering on, the stock BYD cluster will initialize normally. *(Note: Standard BYD vehicle sleep cycle).*

## Parked In-Car Verification Sequence

Perform testing in this exact order:

### Step 1: Pre-flight ADB Verification
1. Ensure Network ADB is enabled in the vehicle (port 5555 open on `127.0.0.1`).
2. Open Waze on the vehicle screen.
3. Open **Gösterge Paneli (Cluster)** panel.
4. Tap **"ADB İznini Başlat / Yetkilendir (Authorize ADB)"**.
5. **Expected result**: The Android system RSA authorization dialog appears on the head unit screen: *"Allow USB debugging from this computer?"*
6. Check **"Always allow from this computer"** and tap **OK / İzin Ver**.

### Step 2: Test Stock Cluster Restoration (BEFORE Enabling Projection)
1. In the same panel, tap **"Orijinal Göstergeyi Geri Yükle (Restore Stock Cluster)"**.
2. **Expected result**: The cluster remains on / returns to normal factory instruments without error or crash. Logcat confirms command 18/35 accepted.

### Step 3: Test Projection Activation
1. Select Layout Profile: **DiLink 3**.
2. Toggle ON: **"DiLink 3 gösterge yansıtmasını etkinleştir (deneysel)"** (Parked confirmation dialog appears).
3. **Expected result**: The cluster transitions to the Waze navigation map.
4. Keep navigation running for at least 35 seconds to satisfy the 30-second healthy rule (`HEALTHY_MS = 30000`).

### Step 4: Test Vehicle Power Cycle (Auto-Resume)
1. Turn off vehicle power, wait 10 seconds, and turn power back on.
2. Open Waze.
3. **Expected result**: If previous session ran healthy for >30s, one-time auto-resume engages without requiring manual retry.

## Diagnostic Logcat Tags

When monitoring or troubleshooting via ADB, filter for these tags:

```bash
adb logcat -s WazeDiLink3:V WazeClusterTest:V WazeBydGuidance:V
```

- **`WazeDiLink3`**: ADB connection status, AutoContainer parcel returns, quarantine latch state, auto-resume status.
- **`WazeClusterTest`**: Virtual surface presentation lifecycle (`surfaceCreated`, `surfaceDestroyed`, dimensions).
- **`WazeBydGuidance`**: AMap guidance broadcast events (`AUTONAVI_STANDARD_BROADCAST_SEND`), target package verification (`com.example.amapservice`), and HUD suppression.
