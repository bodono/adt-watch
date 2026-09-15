"""Build both personal setup APKs; never contacts or installs on a device."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from android_tooling import build_environment

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--offline", action="store_true", help="Use only cached build dependencies")
parser.add_argument("--check", action="store_true", help="Also run local unit tests and Android lint")
args = parser.parse_args()
try:
    env = build_environment()
except RuntimeError as error:
    parser.error(str(error))
runner = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
if not runner.is_file():
    parser.error("Gradle wrapper is missing from this checkout.")
command = [str(runner), "--no-daemon", "--console=plain"]
if args.offline:
    command.append("--offline")
command += [":phone-probe:assembleDebug", ":wear:assembleDebug"]
if args.check:
    command += [":phone-probe:testDebugUnitTest", ":wear:testDebugUnitTest",
                ":phone-probe:lintDebug", ":wear:lintDebug"]
subprocess.run(command, cwd=ROOT, env=env, check=True)
out = ROOT / "build"
out.mkdir(exist_ok=True)
for module, name in [("phone-probe", "phone-probe"), ("wear", "watch-probe")]:
    source = ROOT / module / "build/outputs/apk/debug" / (module + "-debug.apk")
    target = out / (name + ".apk")
    shutil.copyfile(source, target)
    print("Built", target)
if args.check:
    subprocess.run([sys.executable, str(ROOT / "check_artifacts.py")], cwd=ROOT, env=env, check=True)
