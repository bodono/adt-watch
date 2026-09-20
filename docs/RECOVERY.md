# Install and recover ADT Watch

This guide describes v0.25, whose live-query integration is undergoing
validation. Rebuilding the apps does not recreate their private device setup.
The owner completes native widget consent, watch association, routine approval
and both ADT sign-ins on their devices.

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
adb -s WATCH_SELECTOR install -r build/watch-probe.apk
adb -d install -r build/phone-probe.apk
```

`-r` updates an existing matching-signature installation while retaining its
data. If Android reports an incompatible signature, check the restored key;
do not treat uninstalling as a routine update step. Keep the phone and watch
builds at the same version and signature. Update the watch first, then the phone.
Both apps need v0.25 for live-query observation identity and freshness handling.
After updating an earlier installation, complete the new website sign-in and
explicit system selection below; existing native ADT sign-in is not enough.
An existing sign-in and system selection in this helper are retained by an
in-place update. Check live status before repeating that setup.

## Fresh phone setup

Routine watch control requires Android 15 or later and a secure phone screen
lock. Install and sign into UK **ADT Smart Services** in the same Android user
profile as the helper. The native widget contract accepts package
`com.adtuk.adtukalarm` with **versionCode 2307** only. Other versions require a
compatibility review.

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

5. **Sign in for live status and choose the same home.** Open **Set up ADT live
   status…** on the helper's main screen. Sign into the official ADT/Alarm.com
   page shown there and complete any verification in that page. This is a
   separate session from the native ADT app. That page's password and verification code are not extracted; WebView retains session cookies
   in app-private browser storage. Tap **Check live status**. Review the returned
   system, partition and actual state, then tap **Use this ADT system** only if
   it is the same home as both scene widgets. No system is selected automatically.
   Only one system with one partition is currently supported. The status check
   and selection send no alarm command and do not enable watch control.
   After selection, the website closes while the helper keeps its sign-in.
   Later visits can **Check live status** directly; **Open ADT sign-in** reopens
   the website when sign-in or verification is needed.

6. **Enable routine controls once.** With exactly one watch connected, open
   **Watch control access… → Enable watch controls once…**. Choose the associated
   watch. Read **Enable these watch controls?**, check that the associated device
   and connected watch both refer to your watch, then select **I reviewed them —
   enable** only after reviewing both scenes and options. This approval allows
   future one-tap requests while the phone is locked. Enabling it sends no alarm
   request.

7. **Allow ADT background work.** In the phone's native app settings, set the
   **ADT app's battery usage to Unrestricted**. This is ADT's setting, rather
   than just the helper's. Native ADT jobs were delayed by adaptive battery
   restrictions in the personal setup even with normal Battery Saver off.

8. **Optionally allow notification refresh hints.** From the helper's main
   screen, tap **Allow ADT refresh hints…** and enable **ADT Watch alarm status**
   in Android's native notification-access settings. These notifications only
   prompt a read-only ADT query. They do not determine the displayed state or
   confirm an alarm request, and notification access is not required for live
   status queries.

A successful live query establishes status without changing the alarm or
waiting for a new notification. The read-only client uses a fixed part of
Alarm.com's website API, an unofficial integration that may change. It returns
ADT's backend-reported actual state; it does not force the physical panel to
poll. An unsupported, ambiguous or failed response leaves controls unavailable.

## Use the watch

Open **ADT Watch** on the watch. A watch PIN or pattern is optional; unlock an
existing watch lock normally. Keep the phone powered, **locked** and connected,
with the native ADT app and the helper's separate website session signed in.

The single button is red for ADT-reported Armed Stay/Away and requests **Disarm**;
it is green for reported Disarmed and requests **Arm Stay**. Tap once and keep
the app visible while it sends. There is no second confirmation or automatic
alarm retry. The selected action remains fixed. A fresh phone query checks the
actual state before native execution; if the target is already satisfied, no
widget click is made. Otherwise the selected scene is attempted once.

For swipe access, add **ADT Watch** using the watch's tile picker. A coloured
Tile tap opens the app and continues the same action without a second tap.
App/Tile loading and **Refresh** ask the phone to query ADT, without operating
the alarm. Wear OS controls scheduling, so every swipe cannot be guaranteed a
new query. The Tile may briefly load while the phone answers. Status is usable
for less than 60 seconds; the displayed observation age measures time since
its query, even when the alarm has remained unchanged for hours.
See [Google's tile instructions](https://support.google.com/googlepixelwatch/answer/12662644?hl=en-GB).
The computer and USB cable are not required for daily use.

A widget click or a successful message send does not prove a state change.
The helper checks ADT afterward. After 30 seconds, an unresolved request becomes
**Result unconfirmed** and progress stops. A fresh steady ADT state can restore
controls without waiting for a notification; the earlier uncertain request is
not automatically repeated. Native ADT work already queued can execute later.

Earlier native-widget cycles worked with the phone locked and ADT set to
Unrestricted. v0.25 live queries, session longevity and reliable overnight use
still require physical verification.

## Optional automatic login

1. Finish **Set up ADT live status…**, check the matching home, and tap **Use this
   ADT system** to close the website. Complete any ADT verification yourself;
   use its trusted-device option if offered.
2. Open **Automatic ADT login…** on the helper's main screen. Enter your username
   and password directly on the phone, then **Save and test automatic login**.
   Keep the screen open while it checks. This logs in and reads status without
   sending an alarm command. Only a successful test enables automatic recovery.
3. Lock the phone and refresh the watch. On ordinary session expiry the watch
   first shows the sign-in notice; the helper then re-logs in with its own
   20-second budget, reads the same home and sends the watch fresh status, so
   the notice clears by itself.
4. If the saved password changes or ADT asks for verification, use **Open ADT
   sign-in** to finish the official flow, select the same home again, then
   **Test saved login**. Choosing the system again also resumes paused
   automatic attempts; the helper's main screen shows a pause and its check
   code next to the sign-in notice. Failed or cancelled tests leave recovery disabled.
   **Forget saved login** removes its encrypted credentials and key.

Credentials remain encrypted in private phone storage and are not included in
backups. They cannot be recovered from GitHub or transferred with this app's
source. After restarting the phone, unlock it once before background use.
Automatic login is unofficial and still needs real-account and overnight
validation, and it signs in at www.alarm.com only: the screen warns when your
last verified session host is smartservices.adt.co.uk, where it may stop at ADT
verification, so rely on it only after the test succeeds. It does not renew the
separate native ADT app session or retry an
alarm command. Errors display a closed **Check code** that can be shared without
including account details.

## Changes and troubleshooting

- **Grey or uncertain after a tap:** do not repeat the tap immediately. Check
  the actual state in ADT and let queued work settle. After 30 seconds, progress
  ends with an unconfirmed outcome; Refresh makes a new read-only status query.
  The helper cannot recall native ADT work already handed off.
- **Sign-in or verification required:** unlock the phone and return to **Set up
  ADT live status… → Open ADT sign-in**. Finish the official page's sign-in or
  verification, then **Check live status** and review the same system again. The native ADT app's
  existing login does not refresh this separate website session.
- **Grey before any request or Refresh fails:** check the phone connection,
  both sign-ins, selected system and supported ADT version. Website/network
  errors cannot provide a fresh observation. Notification access alone cannot
  repair a failed live query. Use ADT normally while status is unavailable.
- **Check live status fails despite being signed in:** the setup screen's
  **Check code** identifies the failed request stage and error category, with
  an HTTP status when available. Share that code when reporting a problem;
  it contains no account details. For example, `IDENTITIES/RESPONSE_SIZE/200`
  means the account-discovery response exceeded the supported size, rather
  than a failed password. The discovery limit is 4 MiB. A timeout or
  server error can be retried with **Check live status**; signing in again is
  needed when the screen specifically requests sign-in or verification.
- **Repeated "Successful Login" alerts from ADT:** automatic recovery creates
  a new website session and can generate a login alert. The phone attempts a
  status read after ten idle minutes to reduce expiry, but whether this renews
  the server session still needs verification. Its timer never starts a login;
  process death, deep sleep or a failed read can interrupt the checks. A later
  watch request may therefore still need automatic login. Turning the alert
  off under the account's system-event notifications hides genuine logins too.
- **More than one system/partition or an unsupported response:** this version
  cannot select an arbitrary member of a multi-system account or guess an
  unfamiliar state. A compatibility change is needed; keep using ADT directly.
- **An old coloured Tile remains visible:** Wear OS may delay the grey fallback.
  An expired tap still cannot bypass token checks or the phone's live preflight.
  A selected Disarm request is never silently turned into Arm Stay.
- **Changing either scene or widget:** first use **Watch control access… →
  Disable watch controls**. Review the complete scenes, reconfigure the affected
  widget and repeat **Enable watch controls once…**. Widget changes invalidate
  the saved review, including replacing a widget with a similarly named one.
- **Changing the website system:** disable watch controls first. Check that both
  native scenes address the intended home, select its live-status system and
  repeat the scene review and routine approval. The system choice does not
  inspect or rewrite the native scene contents.
- **New phone, watch, cleared app data or lost association:** repeat the relevant
  native setup, website sign-in, system choice and watch review. Do not copy
  widget IDs, session cookies or access records between devices. ADT scenes,
  private signing keys and the two sign-ins are separate recovery items.
- **ADT or its website changes:** keep normal alarm operation available through
  ADT while compatibility is reviewed. Do not bypass native version or response
  validation to make an unsupported integration appear ready.

Keep account details, session cookies, device selectors, pairing codes, private
keys, personal logs and ADT screenshots out of public issues and commits. The
public project contains helper source and inert fixtures, not an ADT APK or
decompiled ADT code.
