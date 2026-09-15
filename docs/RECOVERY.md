# Install and recover ADT Watch

This guide covers v0.17. It distinguishes rebuilding the apps from recreating
their private setup on a phone and watch. The repository provides source;
Android widget consent, watch association, ADT sign-in and notification access
are completed by the owner on their devices.

## Preserve the signing key

Both APKs use the application ID **`dev.personal.adtprobe`** and must be signed
with the same key. Their launcher names differ: **ADT Watch Setup** on the phone
and **ADT Watch** on the watch.

For updates to existing installations, privately back up the original keystore,
key alias, keystore password and key password. Keep this backup separately from
the public repository. A previous APK can be kept privately as a signing
certificate reference, but it cannot recover the private signing key.

The build preserves `.tools/keys/adt-probe.jks` when present. An explicit restored
key can instead be selected with `ADT_WATCH_KEYSTORE`,
`ADT_WATCH_STORE_PASSWORD`, `ADT_WATCH_KEY_ALIAS` and `ADT_WATCH_KEY_PASSWORD`.
`ADT_WATCH_REFERENCE_APK` optionally checks the output against an existing APK's
signer. See [BUILDING.md](../BUILDING.md) for the exact configuration.

A fresh clone without a restored key uses the standard local Android debug
keystore for both modules. That is suitable for a new personal installation;
it cannot update an installation signed by a different key. If the original
key is lost, Android requires uninstalling the old app before installing one
signed with a new key. Uninstalling loses that app's configuration, so plan to
repeat setup on the affected devices. The helper disables Android app-data
backup; do not rely on automatic restore to recover widget IDs or access grants.

## Clone and build

Install Git, Python 3.9 or later, JDK 17 or later (JDK 21 is tested), Android SDK Platform
36, Build Tools 36.0.0 and Platform Tools. Configure the SDK through
`ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`. Set `JAVA_HOME`
to your JDK if it is not already selected.

Clone this repository:

```sh
git clone https://github.com/bodono/adt-watch.git
cd adt-watch
./phone-probe/build.sh --check
```

Restore the signing configuration **before building** if these APKs must update
existing apps. The first build needs internet access for dependencies. Once
dependencies and Robolectric runtimes are cached, use
`./phone-probe/build.sh --offline --check`.

The checked outputs are:

- `build/phone-probe.apk` — phone application
- `build/watch-probe.apk` — Wear OS application

The build does not access devices or operate an alarm. [BUILDING.md](../BUILDING.md)
describes toolchain configuration, reports and signing checks.

## Install on the two devices

First pair the Pixel Watch with the phone through Google's normal watch setup,
if it is not already paired. Follow [Google's Pixel Watch setup instructions](https://support.google.com/googlepixelwatch/answer/12651780?hl=en-GB).
This pairing is separate from the helper's companion association below.

Enable USB debugging on the phone and approve your own computer's native
debugging prompt. To install on the watch, follow Android's
[Wear OS wireless debugging guide](https://developer.android.com/training/wearables/get-started/debug-wifi).
Use the pairing code and connection details currently displayed by your watch;
the pairing and connection ports can differ. Bluetooth pairing with the phone
alone does not make the watch an ADB target.

Use the phone APK on the phone and the Wear APK on the watch. These commands
assume exactly one USB-connected phone. Replace `WATCH_SELECTOR` with your
watch's selector from `adb devices`:

```sh
adb -d install -r build/phone-probe.apk
adb -s WATCH_SELECTOR install -r build/watch-probe.apk
```

`-r` updates an existing matching-signature installation while retaining its
data. If Android reports an incompatible signature, check the restored key;
do not treat uninstalling as a routine update step. Keep the phone and watch
builds at the same version and signature.

## Fresh phone setup

Routine watch control requires Android 15 or later and a secure phone screen
lock. Install and sign into UK **ADT Smart Services** in the same Android user
profile as the helper. v0.17 accepts package `com.adtuk.adtukalarm` with
**versionCode 2307** only. Its widget and English notification formats are
version-specific; an unsupported version needs a code/compatibility review.

1. **Review two complete scenes in ADT.** Create or review `WATCH ARM STAY`
   containing only `SYSTEM / ARM STAY`, and `WATCH DISARM` containing only
   `SYSTEM / DISARM`. Review every action and option yourself. Do not add other
   devices or actions, or choose force-arm/bypass options merely to make setup
   succeed. The helper checks the expected scene label but cannot prove a
   scene's contents from its name.

2. **Configure the Arm Stay widget.** Open **ADT Watch Setup → Advanced setup… →
   Configure Arm Stay scene widget → Choose ADT scene widget…**. Complete
   Android's native widget-host consent if requested, then select the reviewed
   `WATCH ARM STAY` scene in ADT's normal picker. If necessary, tap **Continue
   ADT widget setup…**. On return, check that the preview shows the correct scene
   and says its actions are blocked. Tap **Done**.

3. **Configure the Disarm widget.** Use **Advanced setup… → Configure Disarm
   scene widget** and repeat for `WATCH DISARM`. Both separate widgets must be
   configured before enabling routine controls. **Enable widget taps…** is an
   optional foreground diagnostic, not a setup requirement; an enabled preview
   tap can execute the entire ADT scene.

4. **Associate the watch with this helper.** Use **Advanced setup… → Watch
   pairing → Choose already paired watch…**. Grant Bluetooth/Nearby devices
   access if Android requests it. Select your own watch, check the identity in
   Android's companion-device chooser and approve it there. Nothing is selected
   automatically. If the name is ambiguous, cancel. **Search nearby devices
   instead…** is available if needed. Check the association status before
   leaving this screen.

5. **Enable routine controls once.** With exactly one watch connected, open
   **Watch control access… → Enable watch controls once…**. Choose the associated
   watch. Read **Enable these watch controls?**, check that the associated device
   and connected watch both refer to your watch, then select **I reviewed them —
   enable** only after reviewing both scenes and options. This approval allows
   future one-tap requests while the phone is locked. Enabling it sends no alarm
   request.

6. **Allow ADT state reports.** From the main screen, tap **Allow ADT alarm
   status…** and enable **ADT Watch alarm status** in Android's native
   notification-access settings. Ensure ADT's own alarm-state notifications are
   enabled. The helper reads matching ADT reports and stores normalized state,
   timing, a hashed alarm scope and request/state bookkeeping; it does not
   store notification bodies.

7. **Allow ADT background work.** In the phone's native app settings, set the
   **ADT app's battery usage to Unrestricted**. This is ADT's setting, rather
   than just the helper's. In the verified setup, adaptive battery restrictions
   delayed native ADT jobs even with normal Battery Saver off. Changing ADT to
   Unrestricted released the queued work without changing global power settings.

The setup starts with unknown state unless a usable ADT report is available.
Wait for a real Armed Stay, Armed Away or Disarmed notification from normal ADT
use. If you choose to perform an action in the ADT phone app to establish a
fresh report, do so only when you intend that alarm change and verify the result
there. **Refresh** cannot manufacture a report or infer the state from a scene
name. Conflicting or unrecognized reports leave the control grey.

## Use the watch

Open **ADT Watch** on the watch. A watch PIN or pattern is optional; unlock an
existing watch lock normally. Keep the paired phone powered, **locked** and
connected, with ADT signed in and able to reach its service.

The single button is red for ADT-reported Armed Stay/Away and requests **Disarm**;
it is green for reported Disarmed and requests **Arm Stay**. Tap once. There is
no second confirmation or automatic retry. Keep the watch app visible while it
sends. Grey means no action is available or a request is awaiting a newer ADT
report. Reports over 24 hours old are not offered as coloured controls.

For swipe access, add **ADT Watch** using the watch's tile picker. A coloured
Tile tap opens the app and continues the same action without a second tap.
See [Google's tile instructions](https://support.google.com/googlepixelwatch/answer/12662644?hl=en-GB).
The computer and USB cable are not required for daily use.

Both directions of this Tile flow have worked with the phone locked and ADT
set to Unrestricted, taking a few seconds. Natural overnight idle and broader
compatibility remain unverified.

## Changes and troubleshooting

- **Grey or uncertain after a tap:** do not repeat it. Check the actual alarm
  state in ADT. Work already queued inside ADT can execute when background
  restrictions lift or ADT opens; the helper cannot recall it. A new ADT state
  report is needed before offering another coloured action.
- **Grey before any request:** check the phone connection, notification access,
  supported ADT version and availability of a recent, recognized ADT report.
  Refresh only reads status. Reopening ADT Watch alone does not send an alarm
  command; opening native ADT may let previously queued work run.
- **Changing either scene or widget:** first use **Watch control access… →
  Disable watch controls**. Review the complete scenes again, reconfigure the
  affected widget and repeat **Enable watch controls once…**. Widget setup
  changes invalidate the saved review, including replacing a widget with a
  similarly named one.
- **New phone, watch, cleared app data or lost association:** repeat the relevant
  native setup and explicit watch review. Do not copy widget IDs or access
  records between devices. ADT sign-in, ADT scenes and the signing key are
  separate recovery items.
- **ADT update stops compatibility:** v0.17 deliberately rejects other version
  codes. Keep normal alarm operation available through ADT while the new widget
  and notification contract is reviewed; do not bypass the check as a recovery
  shortcut.

Keep account details, device selectors, pairing codes, private keys, personal
logs and ADT screenshots out of public issues and commits. The public project
contains helper source and inert fixtures, not an ADT APK or decompiled ADT code.
