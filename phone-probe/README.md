# ADT Watch Setup — phone module

The v0.27 companion queries ADT status through a separate authenticated website
session and executes requests through two user-configured native ADT widgets.
The live-query integration is undergoing validation.

Use **Set up ADT live status…** to sign into the official ADT/Alarm.com page,
complete any verification there, then **Check live status** and **Use this ADT
system**. Select the same home as both scene widgets. Only one system with one
partition is supported. The helper does not extract passwords or verification
codes from that page; WebView retains session cookies. Optional **Automatic ADT
login…** stores credentials entered in its native screen with Android Keystore
encryption. A successful explicit test enables bounded re-login on expiry;
fresh verification or rejected credentials require attention on the phone.
The fixed GET status routes are an unofficial website API integration.

Configure and review `WATCH ARM STAY` and `WATCH DISARM`, create a native
companion association and explicitly enable routine controls for the selected
watch. Preview input is blocked by default. Review every scene action and option
in ADT; the name alone cannot prove a scene's contents. Reconfiguring either
widget requires a new review and approval.

Routine control requires Android 15+, a secure locked phone, the approved watch
and UK ADT `com.adtuk.adtukalarm` versionCode 2307. Keep the native ADT app signed
in and set **its battery usage to Unrestricted**. The helper's website sign-in
does not replace the native app's sign-in.

Refresh reads ADT's reported actual state. Notification access is optional and
supplies refresh hints only. A fresh query also precedes an alarm action; if its
target is already satisfied, the service skips the widget click. Otherwise it
invokes the selected listener at most once. It never automatically retries or
resumes a command after process death. Queued native ADT work can run later.

The live ledger separates fresh observations from request outcomes. A cache
lasts less than 60 seconds; unchanged state can have a fresh observation.
Pending progress ends after 30 seconds. An unconfirmed request does not hide a
subsequent fresh steady ADT state or wait indefinitely for a notification.

Build both modules from the repository root with
`./phone-probe/build.sh --check`. Tests use inert fixtures with no device or ADT
access. See [BUILDING.md](../BUILDING.md), [recovery and setup](../docs/RECOVERY.md)
and [architecture](../docs/ARCHITECTURE.md).
