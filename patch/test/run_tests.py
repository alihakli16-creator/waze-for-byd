#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Test conversion/lifecycle on JVM; optionally test Intent types on Android.

The Android tests construct intents but never send broadcasts or start Waze.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-serial', help='Optional adb serial, e.g. emulator-5580')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk'))
android = sdk / 'platforms/android-35/android.jar'
bt = sdk / 'build-tools/36.0.0'
out = root / 'test-out'
if out.exists():
    shutil.rmtree(out)
(out / 'classes').mkdir(parents=True)
sources = [root / 'src/local/waze/cluster' / (name + '.java') for name in
           ('GuidanceFrame', 'GuidanceSession', 'WazeGuidance', 'BydGuidanceBridge', 'StandaloneHudPackets', 'StandaloneHudGate', 'StandaloneHudOutput',
            'WheelKeyPolicy', 'WazeLanes', 'DiLink51Layout', 'ClusterActivityState', 'ClusterActivityMonitor', 'ClusterSettings', 'ClusterStatus', 'ClusterTheme', 'AmapAdapter',
            'VehicleLocalAdb', 'DiLink3Projection')]
sources += sorted((root / 'src/local/waze/diagnostics').glob('*.java'))
sources += [root / 'test/GuidanceTests.java', root / 'test/GuidanceAndroidTests.java', root / 'test/StandaloneHudTests.java',
            root / 'test/ClusterLayoutTests.java', root / 'test/WheelKeyTests.java', root / 'test/DiLink3Tests.java']

def run(*command):
    subprocess.run(list(map(str, command)), check=True)

stubs_dir = root / 'test/stubs'
stubs_sources = sorted(stubs_dir.rglob('*.java')) if stubs_dir.exists() else []
stubs_out = out / 'stubs'
if stubs_sources:
    stubs_out.mkdir(parents=True, exist_ok=True)
    run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-d', stubs_out, *stubs_sources)

sep = os.pathsep
compile_cp = f'{stubs_out}{sep}{android}{sep}{root / "car-api.jar"}' if stubs_sources else f'{android}{sep}{root / "car-api.jar"}'
run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp',
    compile_cp, '-d', out / 'classes', *sources)

test_cp = f'{stubs_out}{sep}{out / "classes"}{sep}{root / "car-api.jar"}{sep}{android}' if stubs_sources else f'{out / "classes"}{sep}{root / "car-api.jar"}{sep}{android}'
run('java', '-cp', test_cp, 'local.waze.cluster.GuidanceTests')
run('java', '-cp', test_cp, 'local.waze.cluster.StandaloneHudTests')
run('java', '-cp', test_cp, 'local.waze.cluster.ClusterLayoutTests')
run('java', '-cp', test_cp, 'local.waze.cluster.WheelKeyTests')
run('java', '-cp', test_cp, 'local.waze.cluster.DiLink3Tests')
if args.android_serial:
    (out / 'dex').mkdir()
    run(bt / 'd8', '--min-api', '29', '--lib', android, '--output', out / 'dex',
        *sorted((out / 'classes').rglob('*.class')), root / 'car-api.jar')
    remote = '/data/local/tmp/waze-for-byd-guidance-tests.dex'
    adb = sdk / 'platform-tools/adb'
    run(adb, '-s', args.android_serial, 'push', out / 'dex/classes.dex', remote)
    try:
        run(adb, '-s', args.android_serial, 'shell', f'CLASSPATH={remote}', 'app_process',
            '/system/bin', 'local.waze.cluster.GuidanceAndroidTests')
    finally:
        run(adb, '-s', args.android_serial, 'shell', 'rm', '-f', remote)
