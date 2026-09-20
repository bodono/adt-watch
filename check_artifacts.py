"""Verify local prototype APKs. Does not discover, access or install on devices."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
from android_tooling import build_environment, build_tools_directory

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--reference-apk', type=Path, default=os.environ.get('ADT_WATCH_REFERENCE_APK'),
                    help='Optional private APK whose signer must match (or ADT_WATCH_REFERENCE_APK)')
args = parser.parse_args()
try:
    ENV = build_environment()
    BUILD_TOOLS = build_tools_directory(ENV)
except RuntimeError as error:
    parser.error(str(error))
PACKAGE = 'dev.personal.adtprobe'
RETIRED = ['LockedArmTestService', 'LockedProbeService', 'NeutralCheckActivity', 'StateRecoveryActivity', 'shortcuts.json']


def require(condition, message='Packaged APK contract does not match the expected configuration'):
    if not condition:
        raise RuntimeError(message)


def run(tool, *args):
    suffix = '.bat' if os.name == 'nt' and tool == 'apksigner' else '.exe' if os.name == 'nt' else ''
    return subprocess.check_output([str(BUILD_TOOLS / (tool + suffix)), *map(str, args)],
                                   env=ENV, text=True, stderr=subprocess.STDOUT)


def signer(apk):
    result = run('apksigner', 'verify', '--verbose', '--print-certs', apk)
    if not ('Verified using v2 scheme (APK Signature Scheme v2): true' in result
            or 'Verified using v3 scheme (APK Signature Scheme v3): true' in result):
        raise RuntimeError('APK must have a verified v2 or v3 signature')
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', result, re.M)
    if len(certs) != 1:
        raise RuntimeError('Expected one verified signer')
    return certs[0]


def verify(name, launcher, permissions):
    apk = ROOT / 'build' / (name + '.apk')
    badging = run('aapt2', 'dump', 'badging', apk)
    require("package: name='dev.personal.adtprobe' versionCode='26' versionName='0.26'" in badging)
    require("minSdkVersion:'30'" in badging and "targetSdkVersion:'36'" in badging)
    require("launchable-activity: name='" + PACKAGE + '.' + launcher + "'" in badging)
    actual_permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    require(actual_permissions == permissions, 'Unexpected packaged permissions')
    manifest = run('aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk)
    require('allowBackup(0x01010280)=false' in manifest)
    require(all(item not in manifest for item in RETIRED))
    with zipfile.ZipFile(apk) as package:
        require(not any(p.startswith('assets/') for p in package.namelist()), 'Unexpected bundled assets')
        for path in package.namelist():
            if path.endswith('.dex'):
                code = package.read(path)
                require(all(item.encode() not in code for item in RETIRED), 'Retired code/config remains')
    if name == 'phone-probe':
        require(manifest.count('E: service ') == 3)
        require('dev.personal.adtprobe.AdtStateListener' in manifest)
        require('android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest)
        require('dev.personal.adtprobe.WatchLinkService' in manifest)
        require('dev.personal.adtprobe.ArmExperimentService' in manifest)
        require('dev.personal.adtprobe.ArmExperimentActivity' in manifest)
        require('com.google.android.gms.wearable.MESSAGE_RECEIVED' in manifest)
        require('/adt-probe/v1/ping' not in manifest, 'The retired ping path must not be registered')
        require('/adt-probe/v2/alarm/prepare' in manifest and '/adt-probe/v2/alarm/commit' in manifest)
        require('/adt-probe/v3/toggle/prepare' in manifest and '/adt-probe/v3/state/query' in manifest)
    else:
        require(manifest.count('E: service ') == 2)
        require('dev.personal.adtprobe.AlarmTileService' in manifest)
        require('dev.personal.adtprobe.WatchStateListener' in manifest)
        require('com.google.android.wearable.permission.BIND_TILE_PROVIDER' in manifest)
        require('/adt-probe/v3/state/report' in manifest)
        require('DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' in manifest)
        require('protectionLevel(0x01010009)=0x00000002' in manifest)
        require('android.hardware.type.watch' in manifest)
        require('com.google.android.wearable.standalone' in manifest)
        require('com.adtuk.adtukalarm' not in manifest)
    cert = signer(apk)
    return {'file': str(apk.relative_to(ROOT)), 'bytes': apk.stat().st_size,
            'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'signer_sha256': cert, 'permissions': sorted(actual_permissions)}


phone = verify('phone-probe', 'MainActivity', {
    'android.permission.INTERNET', 'android.permission.BIND_APPWIDGET', 'android.permission.BLUETOOTH',
    'android.permission.BLUETOOTH_CONNECT', 'android.permission.FOREGROUND_SERVICE',
    'android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND'})
watch = verify('watch-probe', 'WatchActivity', {'dev.personal.adtprobe.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'})
if phone['signer_sha256'] != watch['signer_sha256']:
    raise RuntimeError('Phone/watch signers differ')
legacy_reference = ROOT / 'build/phone-probe-v0.8-installed.apk'
previous = args.reference_apk.expanduser() if args.reference_apk is not None else (
    legacy_reference if legacy_reference.is_file() else None)
if previous is not None:
    if not previous.is_file():
        raise RuntimeError('The explicitly configured signing reference APK is missing')
    if signer(previous) != phone['signer_sha256']:
        raise RuntimeError('APK signing identity differs from the upgrade reference')
report = {'version': '0.26', 'device_access': False, 'artifacts': [phone, watch],
          'same_phone_watch_signer': True, 'upgrade_signer_checked': previous is not None,
          'same_signer_as_installed_v08': True if previous == legacy_reference else None,
          'no_assets_or_retired_code': True}
(ROOT / 'build/artifact-checks.json').write_text(json.dumps(report, indent=2) + '\n')
print('Artifact checks passed: versions, launchers, permissions, no assets/retired code, '
      'signatures, matching phone/watch signer.'
      + (' Upgrade signing reference also matched.' if previous is not None else ''))
