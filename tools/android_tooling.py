"""Locate standard Android tooling, with optional fallbacks for the original workspace."""
from pathlib import Path
import os
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS_VERSION = "36.0.0"


def _property_value(path, key):
    """Read one Java-properties value, including Android Studio's escaped SDK paths."""
    if not path.is_file():
        return None
    logical = ""
    for physical in path.read_text(encoding="utf-8").splitlines():
        logical += physical.lstrip() if logical else physical
        slashes = len(logical) - len(logical.rstrip("\\"))
        if slashes % 2:
            logical = logical[:-1]
            continue
        match = re.match(r"^\s*" + re.escape(key) + r"\s*[:=]\s*(.*)$", logical)
        logical = ""
        if match:
            def unescape(match):
                if match.group(1):
                    return chr(int(match.group(1), 16))
                return {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(match.group(2), match.group(2))
            return re.sub(r"\\u([0-9a-fA-F]{4})|\\(.)", unescape, match.group(1))
    return None


def sdk_directory(env, root=ROOT):
    # Match AGP: local.properties takes precedence over environment variables.
    configured = _property_value(root / "local.properties", "sdk.dir")
    configured = configured or env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    candidate = Path(configured).expanduser() if configured else root / ".tools/sdk"
    if not candidate.is_absolute():
        candidate = root / candidate
    if not candidate.is_dir():
        raise RuntimeError("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties; install Android platform 36 and build-tools 36.0.0.")
    return candidate.resolve()


def build_environment(root=ROOT, source=None):
    env = dict(os.environ if source is None else source)
    if not env.get("JAVA_HOME"):
        for candidate in (root / ".tools/jdk/Contents/Home", root / ".tools/jdk"):
            if (candidate / "bin/javac").is_file():
                env["JAVA_HOME"] = str(candidate)
                break
    # Otherwise the wrapper/AGP uses the caller's JDK 17+ on PATH.
    if "GRADLE_USER_HOME" not in env and (root / ".tools/gradle-home").is_dir():
        env["GRADLE_USER_HOME"] = str(root / ".tools/gradle-home")
    sdk = sdk_directory(env, root)
    if not env.get("ANDROID_HOME"):
        env["ANDROID_HOME"] = str(sdk)
    return env


def build_tools_directory(env, root=ROOT):
    directory = sdk_directory(env, root) / "build-tools" / BUILD_TOOLS_VERSION
    if not directory.is_dir():
        raise RuntimeError("Android SDK build-tools 36.0.0 are missing. Install them with Android Studio or sdkmanager.")
    return directory
