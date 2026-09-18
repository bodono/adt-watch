# ADT Watch Setup — phone module

The v0.18 Android companion hosts two user-configured native UK ADT scene widgets
and handles requests from one explicitly approved watch. It uses the installed
ADT app for alarm execution; it does not contain ADT credentials or a direct
ADT service client.

Fresh setup configures both `WATCH ARM STAY` and `WATCH DISARM` widgets, creates a
native companion association, then enables routine controls after an explicit
review. The owner must review every scene action and option in ADT. Widget
previews are blocked by default, and setup itself sends no alarm request.

Routine use requires Android 15+, a secure and locked phone, the approved watch,
and UK ADT package `com.adtuk.adtukalarm` versionCode 2307. Set **ADT's app battery
usage to Unrestricted**. Reconfiguring either widget invalidates the prior review;
enable controls again after reviewing the change.

Notification access supplies the latest accepted ADT alarm-state report. The
helper records normalized state and timing, a hashed scope and request/state
bookkeeping; it does not store notification bodies. A widget invocation means
only that the native widget listener was invoked. New state is shown after ADT
reports it.

The short service validates a fresh state-bound request and matching commit,
invokes one widget listener at most once, and does not automatically retry or
resume a command after process death. Previously queued ADT work can run later.

Build both modules from the repository root with
`./phone-probe/build.sh --check`. Tests use inert fixtures, with no device or ADT
access. See [BUILDING.md](../BUILDING.md), [recovery and setup](../docs/RECOVERY.md)
and [architecture](../docs/ARCHITECTURE.md).
