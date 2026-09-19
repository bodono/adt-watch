# How ADT Watch works

The v0.25 architecture separates status queries from alarm execution. The phone
and Wear modules share application ID `dev.personal.adtprobe` and a signing
identity. Google Play Services carries bounded, source-matched messages between
them. Live-query integration is undergoing validation.

| Part | Responsibility |
| --- | --- |
| `phone-probe` | Separate website sign-in and live status client, native scene widgets, companion/routine approval and brief request service |
| `wear` | One alarm button, Tile, expiring state cache and user-initiated request flow |
| `shared` | Fixed action enum, strict message formats and request/state validation |

## Authentication and status authority

The owner signs into the official ADT/Alarm.com page in an embedded WebView.
This is a separate session from the native ADT app. The helper does not read that
app's private session or extract passwords or verification codes. WebView keeps
session cookies in app-private storage; the status client uses those cookies
for authenticated queries. No JavaScript bridge is installed. Optional saved
credentials are entered separately in a native screen; they are never extracted
from the WebView. Additional verification can still require the owner.

After explicit system selection, setup destroys the website view while retaining
its cookies and the selected identifiers. Later setup visits can query that
saved session immediately; only **Open ADT sign-in** reopens the website. An
unfinished login or verification page stays open across background/foreground
transitions. This removes the completed page's JavaScript from the background;
it does not extend or guarantee the server's session lifetime.
Each setup query has a cancellable session wrapper. After cancellation or
completion, that attempt cannot read or overwrite the shared session cookies.

### Optional automatic login

`AdtCredentialStore` encrypts both username and password with AES-256-GCM and
an Android Keystore key. The credential-encrypted app storage is unavailable
before the first device unlock after reboot. The key permits subsequent
locked-phone use, without a per-use biometric prompt. Backups are disabled.
The native entry screen blocks screenshots and view-state persistence, clears
inputs on exit, and provides **Forget saved login**.

`AdtSessionRecovery` enables background use only after an explicit phone test
logs in and reads the already selected system/partition. On a status
authentication failure it serializes one login attempt inside the existing
query deadline and requires a fresh, matching status response before returning
state. It never sends or replays an alarm action. An attempt is recorded before
submission; transient failures have a five-minute retry delay, while rejected
credentials, renewed verification and unsupported flows pause attempts until
an explicit successful test. A cancelled or failed retest cannot enable recovery.

`AdtLoginClient` is separate from the GET-only status client. It reads the
verified Alarm.com login form and sends credentials once to the fixed HTTPS
`www.alarm.com/web/Default.aspx` endpoint. It never follows a credential POST
redirect. Each attempt uses an isolated temporary cookie jar, seeded only with
the same-app trusted-device cookie. A successful read of the selected home
commits the new cookies; failed tests leave the existing cookie jar unchanged
and cannot pass using its existing authentication. ADT may independently
invalidate older server sessions during a new login. MFA challenges must be
completed in the official website. Requests, responses and errors have only
closed diagnostics. No password, cookie or account body is logged. Starting a
new login invalidates older native sessions' cookie writes, and interactive
website sign-in suspends automatic attempts. Status reads may follow a session
to the ADT portal host, but login is fixed to www.alarm.com: a trusted-device
cookie held on the ADT host is not seeded, so such an account's automatic login
can stop at verification. The automatic-login screen says so whenever the last
verified session host is not www.alarm.com.

This is an unofficial login flow. The branded ADT login page, CAPTCHA, changed
website forms and server-mandated verification are not automatically solved.
There is no idle keepalive or guarantee of uninterrupted overnight access.

The client permits only fixed HTTPS GET routes on Alarm.com's website API.
Setup discovers the account's selected system through the identities endpoint;
that response may include substantial portal configuration and has a 4 MiB cap.
Routine checks fetch the saved partition and accept the response only when it
carries the bound partition id and names the saved system as its owner. A
response without that relationship is followed by a read of the saved system,
which must list the partition as its sole partition; the saved system id is
never assumed for a response. They omit identity discovery and retain a 256 KiB
cap per response.
Cookies are read and stored for each exact API URL so path-scoped sessions work.
The client validates the returned system and partition relationships, state fields, content
type and response bounds. It currently accepts exactly one system and one
partition. The owner explicitly selects them and checks that they match the
home controlled by the scene widgets. Unsupported or ambiguous responses do
not provide actionable state. This is an unofficial website integration; it is
not a public ADT API contract or a forced poll of the physical alarm panel.

The backend's actual state is authoritative. Desired state or an outstanding
request does not prove completion. ADT notifications can prompt a query, but
their text and any manually entered state do not supply control authority.

`AdtLiveLedger` accepts source-bound observations from queries lasting at most
10 seconds. It rejects expired, out-of-order and mismatched responses. Every
accepted query gets a new observation identity; unchanged actual state keeps
the same state revision. Freshness is conservatively measured from query start,
independently of how long the alarm has remained in that state. A usable cache
lasts less than 60 seconds and is invalidated by reboot or clock rollback.
Query failures also gate availability rather than presenting cached success as
the result of a failed refresh.

## Setup and execution

The owner configures separate ADT widgets for `WATCH ARM STAY` and `WATCH DISARM`
through Android's native widget consent and ADT's scene picker. Preview input
is blocked by default. The helper checks the scene label; the owner must review
the actual scene contents and options. Routine approval binds the reviewed
widget revisions to one native companion association and one explicitly chosen
connected Wear node. Widget reconfiguration requires another review and approval.

A positive watch tap selects one fixed action associated with the displayed
state revision. A Tile supplies a short-lived, one-use launch token for that
action. Rendering, ordinary launches and restored launch history do not authorize
commands. Both devices check the source, action, identity and deadline; watch
focus loss, pause or lock cancels an uncommitted attempt. A matching commit
cannot cold-start the phone service, and process death never resumes a command.

A tap the phone cannot serve (unlocked, a session still closing, widget setup
open, or no approved watch) is declined before any ADT read. Otherwise, before
native execution, the phone makes a fresh read-only query; the watch allows
fifteen seconds for the challenge so that read, the service start and the
widget's settle time fit inside its deadline. If ADT already
reports the selected action's target state, no widget click is needed. The
request is never reinterpreted as the opposite action. Otherwise the phone
prepares the selected widget and uses the matched challenge/commit exchange to
attempt it once. This exchange requires no second user confirmation.

After final widget validation and immediately before its listener is invoked,
the phone durably records the request as pending. A listener returning false or
throwing may still have attempted a handoff, so that remains uncertain. Only a
definitely unattempted command is rejected. ADT executes its own authenticated
work and may queue it; its battery setting must permit background execution.
The helper cannot recall work handed to ADT and never retries an alarm command.

## Observation and request outcomes

The ledger stores actual state separately from command outcome. A steady target
state from a query started after dispatch can confirm the pending request. The
phone polls for that one second after each completed check for up to 30 seconds.
While a request is pending or ADT reports busy, shared reads must be less than
one second old; steady-state reads retain the three-second reuse window. A
confirmation read must have begun after dispatch. ADT notification hints queue
an immediate fresh read, with one coalesced follow-up if another hint arrives
during that read, so the later event is not lost. The phone tells the watch
when something changed; a caller that merely waited for another read in
progress is answered from the ledger rather than recorded as a failure. A tap's
preflight never reuses an earlier read; when its own read cannot complete, that
tap is declined and the display keeps the earlier observation. A read that
fails for a transient reason keeps reporting a still-fresh observation while no
request outcome depends on it; an observation made inside a request's window is
never re-reported as that request's settled result.
Provider busy responses remain transient. Request progress ends after 30
seconds; missing confirmation becomes `UNCONFIRMED`, not an indefinite wait for
a notification. Fresh steady ADT state remains authoritative and can restore
controls even when the earlier request's outcome is unconfirmed.

State reports carry both state revision and observation identity so a new query
of an unchanged state is distinguishable from an old cache. A completed-request
identity is attached only to a qualifying observation. Message delivery alone,
an unsolicited hint or a native widget invocation cannot manufacture state.
A delayed READY hint for the exact observation already accepted in a correlated
reply is ignored while that observation remains fresh: it cannot hide the
confirmed control or extend its lifetime. Other hints still prompt verification.

App/Tile loading, Refresh, update hints and completed attempts start bounded
read-only status checks. Status retries contain no alarm command. The Tile
waits for the phone within its original request, up to eight seconds, rather
than relying on Wear OS to promptly replace an intermediate loading frame.
Recovery windows, one-query-at-a-time coalescing and passive cooldowns bound
background work. Wear OS may delay Tile entry events and visual updates.

Tiles include an expiring current entry and a grey Refresh fallback. Even if a
renderer keeps an old coloured entry visible, the app validates the token and
state before beginning a request, and the phone performs its own live preflight.
Red offers Disarm for reported Armed Stay/Away; green offers Arm Stay for
reported Disarmed. Stale, unknown, unavailable or busy state cannot offer a
coloured control.

## Verification boundaries

Pure and Robolectric tests use invented HTTP responses, inert widgets and
simulated lifecycle events. They do not contact ADT or operate an alarm.
Earlier personal-device checks verified the native widget route with a locked
phone and ADT battery usage Unrestricted. They do not establish v0.25 live-query
compatibility, session longevity or reliable overnight operation. Those require
separate physical verification.

For an uncertain or legacy possible-send record, the watch makes one final
read-only query at the 30-second boundary. That final attempt has a 10-second
deadline; it does not retry itself or send an alarm command. Status freshness
includes the matched query round trip conservatively, so transit time cannot
extend the displayed observation beyond its lifetime.

Setup failures have closed diagnostic codes containing only a request stage,
error category and optional HTTP status. They never include cookies, URLs,
account identifiers, response bodies or exception messages. Starting another
check, a UI timeout or cancellation replaces the previous diagnostic.

Status timing logs contain only elapsed times, closed state/status values and
booleans. They distinguish notification arrival, live-query completion and
watch delivery without notification text, account IDs, cookies or credentials.
