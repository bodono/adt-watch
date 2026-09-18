# ADT Watch

A personal, unofficial Wear OS app for controlling a UK ADT Smart Services
alarm through the ADT app already installed on a paired Android phone.
It is not affiliated with or supported by ADT.

v0.19 provides one large alarm button and a swipeable **ADT Watch** tile:

| Colour | Latest ADT report | One tap requests |
| --- | --- | --- |
| Red | Armed Stay or Armed Away | **Disarm** |
| Green | Disarmed | **Arm Stay** |
| Grey | Unknown, stale, unavailable or waiting for a result | No alarm action |

There is no second confirmation. A coloured tile tap opens the app and continues
that same request. A watch PIN or pattern is optional; an existing watch lock is
respected. The app does not automatically retry an alarm request.

The paired phone is required: keep it powered, **locked**, connected and signed
into ADT. Set the **ADT app's battery usage to Unrestricted** so Android allows
its queued work to run. Installation uses a computer; everyday use needs no
computer or USB cable.

The colour comes from ADT's latest accepted alarm-state notification, with its
age shown on the watch. It is not a continuous live reading. Sending a request
does not change the colour; the app waits for a new ADT report. **Refresh** asks
the phone for status without operating the alarm. If the result stays grey,
check ADT before another request: queued ADT work can execute later.

Opening the app or loading a stale Tile automatically checks status; a separate
Refresh tap normally isn't needed. The Tile draws a checking frame immediately
while fetching the phone's reply, and accepted ADT change notifications prompt a faster status
check. Wear OS schedules Tile updates, so an immediate refresh on every swipe
is not guaranteed. Failed or incomplete checks are retried for up to 30 seconds;
alarm commands are never retried. A missing ADT report keeps controls grey, even
if the phone itself is reachable.

## Setup and recovery

The phone app, **ADT Watch Setup**, hosts two native ADT scene widgets and records
your explicit approval of one associated watch. Both widgets must be configured
and reviewed before enabling watch controls. Routine use then needs no per-use
phone preparation.

- [Build and validate](BUILDING.md)
- [Install, configure and recover on another computer](docs/RECOVERY.md)
- [How the phone, watch and state reports fit together](docs/ARCHITECTURE.md)

Current compatibility is deliberately narrow: routine control requires Android
15 or later, a secure phone screen lock, Google Play Services and UK ADT Smart
Services (`com.adtuk.adtukalarm`) **versionCode 2307**. The widget contract and
English state notifications are checked against that version; other ADT apps
and versions are rejected. The Wear APK has minimum Android API 30.

Both actions and their red/green Tile updates have worked on one personal Pixel
phone/watch setup with the phone locked and ADT set to Unrestricted. They take a
few seconds. Overnight use of v0.17 exposed missed status updates and grey
controls after successful disarming. v0.18 added bounded status recovery;
v0.19 also refreshes stale Tile requests and accelerates notification follow-up.
Reliable overnight operation and wider device/account compatibility still need
physical verification. Offline tests use simulated widgets and do not contact ADT.

## Source and signing

This repository contains the helper source and inert tests. It contains no ADT
APK, decompiled ADT source, account credentials, personal scene data or private
signing key. ADT remains responsible for authentication and the alarm request.

Keep a separate private backup of the signing key, its alias and passwords if
you want to update existing installations. A source clone alone cannot recover
that signing identity. See [recovery](docs/RECOVERY.md#preserve-the-signing-key).

MIT licensed. Copyright 2026 Brendan O'Donoghue. See [LICENSE](LICENSE).
