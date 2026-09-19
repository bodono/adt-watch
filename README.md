# ADT Watch

A personal, unofficial Wear OS app for controlling a UK ADT Smart Services
alarm through the ADT app on a paired Android phone. It is not affiliated with
or supported by ADT or Alarm.com.

The v0.22 integration uses a read-only ADT website query for status and native
ADT scene widgets for alarm requests. This version is being validated; earlier
personal-device checks do not establish reliability of the new live-query flow.

The watch has one large alarm button and a swipeable **ADT Watch** Tile:

| Colour | Fresh ADT-reported state | One tap requests |
| --- | --- | --- |
| Red | Armed Stay or Armed Away | **Disarm** |
| Green | Disarmed | **Arm Stay** |
| Grey | Unknown, stale, unavailable or busy | No alarm action |

There is no second confirmation. A coloured Tile tap opens the app and continues
that same request. A watch PIN or pattern is optional; an existing watch lock is
respected. The app never automatically retries an alarm command.

Keep the paired phone powered, **locked**, connected and signed into the native
ADT app. Set the **ADT app's battery usage to Unrestricted** so Android allows
its queued work to run. Installation uses a computer; everyday use needs no
computer or USB cable.

## Live status

The phone helper also needs a **separate sign-in** through its embedded official
ADT/Alarm.com page. Enter your password and any verification code in that page.
The helper does not extract or store them; WebView retains the authenticated
session cookies in its private browser storage. An expired session requires
signing in again on the phone.

The status client uses a fixed, read-only part of Alarm.com's website API. This
is an unofficial integration, not a supported public ADT API. It supports one
system with one partition, selected explicitly during setup. That must be the
same home controlled by both configured scene widgets.

App/Tile status checks and **Refresh** ask the phone to query ADT. The colour
comes from ADT's reported actual state; notification messages are only hints to
refresh. Status observations expire after at most 60 seconds. Their age means
time since the query, not time since the alarm last changed. This is a backend
status query, not a forced physical-panel poll. Wear OS controls Tile scheduling,
so a new query on every swipe is not guaranteed.

Before handing an alarm request to the native widget, the phone queries ADT
again. If the selected action's target state is already satisfied, it skips the
widget click. Otherwise it attempts the selected action once and checks status.
A send acknowledgement or widget click never invents a successful state change.
After 30 seconds an unresolved request becomes **Result unconfirmed**; fresh,
steady ADT state remains usable rather than waiting indefinitely for a
notification. Check ADT before retrying an uncertain request: native ADT work
already queued can execute later.

## Setup and recovery

**ADT Watch Setup** hosts separate `WATCH ARM STAY` and `WATCH DISARM` native ADT
scene widgets. Review both scenes, configure both widgets, associate the watch,
select the matching live-status system and explicitly enable watch controls.
Routine use then needs no per-use phone preparation.

- [Build and validate](BUILDING.md)
- [Install, configure and recover on another computer](docs/RECOVERY.md)
- [How live status and native alarm requests fit together](docs/ARCHITECTURE.md)

Compatibility is deliberately narrow: routine control requires Android 15 or
later, a secure phone screen lock, Google Play Services and UK ADT Smart Services
(`com.adtuk.adtukalarm`) **versionCode 2307**. Other native ADT versions need a
widget-contract review. Multiple website systems or partitions are rejected;
website response changes may also require an update. The Wear APK has minimum
Android API 30. Wider device/account compatibility and reliable overnight
operation still need physical verification. Offline tests do not contact ADT.

## Source and signing

The repository contains helper source and inert tests, with no ADT APK,
decompiled ADT source, credentials, session cookies, personal scene data or
private signing key. The native ADT app authenticates and executes alarm
requests; the helper's separate website session is used only by its status client.

Keep a separate private backup of the signing key, alias and passwords to update
existing installations. A source clone alone cannot recover that identity.
See [recovery](docs/RECOVERY.md#preserve-the-signing-key).

MIT licensed. Copyright 2026 Brendan O'Donoghue. See [LICENSE](LICENSE).
