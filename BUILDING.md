# Building ADT Watch

The phone and watch are separate APKs with the same application ID and signing
identity. Build and install them as a pair. These are personal development builds;
this repository does not provide a Play Store release pipeline.

## Requirements

- JDK 17 or newer; JDK 21 is used for validation and CI.
- Python 3.9 or newer.
- Android SDK platform 36, Build Tools 36.0.0 and Platform Tools.
- Internet access for the first Gradle and Maven dependency downloads.

Use Android Studio's SDK Manager, or install the required SDK packages with the
Android command-line tools:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK directory. You
can instead create an untracked `local.properties` containing `sdk.dir=/path/to/sdk`.
`ANDROID_SDK_ROOT` is also accepted. No local device, ADT APK or ADT credentials are
needed to build or run the automated checks.

## Build and check

From the repository root on macOS or Linux:

```sh
./phone-probe/build.sh --check
```

The checked-in wrapper downloads Gradle 8.13 and verifies its pinned distribution
checksum. AGP 8.13.2 and the app dependencies are pinned in the Gradle files. The
helper script respects an explicitly set `JAVA_HOME` and `GRADLE_USER_HOME`.

The command builds both APKs, runs the phone/watch JVM and Robolectric tests,
runs Android lint, and checks packaged versions, permissions, components,
absence of personal assets and matching signing certificates. Tests use inert
fixtures; they do not operate a real alarm or connect to a device.

Outputs:

| File | Purpose |
| --- | --- |
| `build/phone-probe.apk` | Phone app, labelled ADT Watch Setup |
| `build/watch-probe.apk` | Watch app and Tile, labelled ADT Watch |
| `build/artifact-checks.json` | APK checks and certificate fingerprints |
| `phone-probe/build/reports/` | Phone test and lint reports |
| `wear/build/reports/` | Watch test, lint and fixture-image reports |

After all dependencies, including Robolectric's Android runtime, are cached:

```sh
./phone-probe/build.sh --offline --check
```

`./gradlew :phone-probe:assembleDebug :wear:assembleDebug` is also available for
normal Gradle development; the APK copying and final artifact checks belong to
the Python helper script.

## Signing and recovery

A fresh clone uses Android's standard local debug keystore for both modules. That
key is not the original developer's key, and APKs built with it cannot update an
installation signed with a different key. The same package name alone is not
enough. Keep a private backup of the key used for your own installations.

To rebuild with an existing private key, set all four variables before building:

```sh
export ADT_WATCH_KEYSTORE="/absolute/private/path/adt-watch.jks"
export ADT_WATCH_STORE_PASSWORD="your-private-keystore-password"
export ADT_WATCH_KEY_ALIAS="your-key-alias"
export ADT_WATCH_KEY_PASSWORD="your-private-key-password"
export ADT_WATCH_REFERENCE_APK="/absolute/private/path/previous-phone.apk"
./phone-probe/build.sh --check
```

Use a private shell session or secret manager for real credentials. Do not put
them in tracked files, issue reports, CI logs or this repository. The optional
reference APK makes the checker verify that the new pair matches the old signing
identity before you install it. A supplied missing or mismatched reference is an
error. You can also pass `--reference-apk /path/to/previous.apk` directly to
`python3 check_artifacts.py` after building.

The corresponding Gradle properties are `adtWatchKeystore`,
`adtWatchStorePassword`, `adtWatchKeyAlias` and `adtWatchKeyPassword`.
Environment variables avoid placing passwords in Gradle command arguments.

For recovery of the original development checkout, the untracked
`.tools/keys/adt-probe.jks` is recognised automatically if present. Its original
standard debug credentials are retained. A private historical signing reference,
if present locally, is also checked. Neither file is required in a clean clone,
and neither is included in Git. Existing local `.tools` toolchain/cache paths are
fallbacks; a new machine can use the standard SDK/JDK setup above.

Android documents its signing requirements in
[Sign your app](https://developer.android.com/studio/publish/app-signing).
See [Recovery and setup](docs/RECOVERY.md) for restoring devices and permissions.

## Continuous integration

GitHub Actions builds and checks a clean checkout on Ubuntu with JDK 21 and the
required Android SDK packages. CI uses an ephemeral debug signing key and uploads
validation reports only. It does not install apps, access an alarm, publish APKs
or use your private signing key. Passing CI does not establish compatibility with
additional ADT versions or natural overnight reliability.
