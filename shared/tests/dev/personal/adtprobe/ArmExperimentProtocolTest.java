package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Plain-Java, transport-free protocol checks. No Android or real command delivery. */
public final class ArmExperimentProtocolTest {
    private static final String REQUEST = "12345678-1234-4234-8234-123456789abc";
    private static final String OTHER_REQUEST = "12345678-1234-4234-8234-123456789abd";
    private static final String CHALLENGE = "abcdef12-1234-4234-8234-123456789abc";
    private static final String OTHER_CHALLENGE = "abcdef12-1234-4234-8234-123456789abd";
    private static final String NODE = "local-test-phone";
    private static final long START = 100_000;
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.US_ASCII); }
    private static ArmExperimentProtocol.Attempt awaiting(AlarmAction action) {
        ArmExperimentProtocol.Attempt attempt = new ArmExperimentProtocol.Attempt(action, REQUEST, START);
        check(attempt.selectTarget(NODE, START + 1), "A single target can be selected");
        check(attempt.prepare(START + 2) != null, "The selected target permits one prepare");
        return attempt;
    }
    private static ArmExperimentProtocol.Attempt ready(AlarmAction action, long delay) {
        ArmExperimentProtocol.Attempt attempt = awaiting(action);
        check(attempt.acceptChallenge(ArmExperimentProtocol.encodeChallenge(action, REQUEST, CHALLENGE), NODE,
            START + delay), "Matching timely challenge should make confirmation possible");
        return attempt;
    }

    private static void checkAction(AlarmAction action) {
        byte[][] examples = {
            ArmExperimentProtocol.encodePrepare(action, REQUEST),
            ArmExperimentProtocol.encodeChallenge(action, REQUEST, CHALLENGE),
            ArmExperimentProtocol.encodeCommit(action, REQUEST, CHALLENGE),
            ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE, ArmExperimentProtocol.Outcome.REQUESTED),
            ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE, ArmExperimentProtocol.Outcome.REJECTED)
        };
        for (byte[] example : examples) {
            check(example.length <= ArmExperimentProtocol.MAX_PAYLOAD_BYTES, "Valid message must be bounded");
            ArmExperimentProtocol.Message parsed = ArmExperimentProtocol.parse(example);
            check(parsed != null && parsed.action == action, "Every message kind preserves its action");
            check(ArmExperimentProtocol.parse(bytes(text(example) + "\n")) == null, "Trailing fields are rejected");
            check(ArmExperimentProtocol.parse(bytes(" " + text(example))) == null, "Leading whitespace is rejected");
            check(ArmExperimentProtocol.parse(bytes(text(example).replace(action.name(), "ARM_AWAY"))) == null,
                "Unknown actions are rejected for every kind");
            check(ArmExperimentProtocol.parse(bytes(text(example).replace(action.name(), action.name().toLowerCase()))) == null,
                "Actions require exact spelling");
            check(ArmExperimentProtocol.parse(bytes(text(example).replace(action.name() + "\n", ""))) == null,
                "Every kind requires an explicit action");
            String oldWire = text(example).replace("ADT-ALARM/2", "ADT-ARM/1")
                .replace(action.name() + "\n", "");
            check(ArmExperimentProtocol.parse(bytes(oldWire)) == null, "Previously valid v1 messages are rejected");
        }
        AlarmAction other = action == AlarmAction.ARM_STAY ? AlarmAction.DISARM : AlarmAction.ARM_STAY;
        check(ArmExperimentProtocol.parse(null) == null, "Null is rejected");
        check(ArmExperimentProtocol.parse(new byte[0]) == null, "Empty payload is rejected");
        check(ArmExperimentProtocol.parse(new byte[257]) == null, "Oversized payload is rejected");
        byte[] invalidAscii = Arrays.copyOf(examples[0], examples[0].length);
        invalidAscii[0] = (byte) 0xff;
        check(ArmExperimentProtocol.parse(invalidAscii) == null, "Non-ASCII is rejected");
        check(ArmExperimentProtocol.parse(bytes(text(examples[0]).replace("PREPARE", "DISARM"))) == null,
            "An action name cannot replace a protocol kind");
        check(ArmExperimentProtocol.parse(bytes(text(examples[3]).replace("REQUESTED", "ARMED"))) == null,
            "An unverified request cannot claim an armed outcome");
        check(ArmExperimentProtocol.parse(bytes(text(examples[0]).replace(REQUEST, "1-1-1-1-1"))) == null,
            "Noncanonical UUID syntax is rejected");
        check(ArmExperimentProtocol.parse(bytes(text(examples[0]).replace(REQUEST, REQUEST.toUpperCase()))) == null,
            "Uppercase UUIDs are rejected");
        check(ArmExperimentProtocol.parse(examples[1], ArmExperimentProtocol.Kind.COMMIT) == null,
            "A challenge cannot satisfy the commit path");
        check(ArmExperimentProtocol.parse(bytes(text(examples[1]).replace("\n", "\r\n"))) == null,
            "CRLF is rejected");
        check(ArmExperimentProtocol.parse(bytes("ADT-ALARM/2\nRESULT\n" + action.name() + "\n" + REQUEST + "\nREJECTED")) == null,
            "A result without a challenge is rejected");
        boolean invalidEncode = false;
        try { ArmExperimentProtocol.encodeCommit(action, REQUEST, ""); }
        catch (IllegalArgumentException expected) { invalidEncode = true; }
        check(invalidEncode, "Encoder also rejects an empty challenge");
        invalidEncode = false;
        try { ArmExperimentProtocol.encodePrepare(null, REQUEST); }
        catch (IllegalArgumentException expected) { invalidEncode = true; }
        check(invalidEncode, "Encoder cannot silently choose an absent action");
        invalidEncode = false;
        try { new ArmExperimentProtocol.Attempt((AlarmAction) null, START); }
        catch (IllegalArgumentException expected) { invalidEncode = true; }
        check(invalidEncode, "Attempt cannot silently choose an absent action");

        ArmExperimentProtocol.Attempt attempt = new ArmExperimentProtocol.Attempt(action, REQUEST, START);
        check(attempt.prepare(START) == null, "Prepare requires a selected node");
        check(attempt.commit(START) == null, "Commit requires a challenge");
        check(!attempt.selectTarget("", START), "Empty source cannot be selected");
        check(attempt.selectTarget(NODE, START), "A valid source is selected");
        check(!attempt.selectTarget("second-node", START), "Target selection cannot change");
        check(attempt.prepare(START) != null && attempt.prepare(START) == null, "Prepare is one-shot");
        check(!attempt.acceptChallenge(examples[1], "second-node", START + 1), "Wrong-source challenge rejected");
        check(!attempt.acceptChallenge(ArmExperimentProtocol.encodeChallenge(other, REQUEST, CHALLENGE), NODE,
            START + 1), "A matching identity for the other action cannot make this attempt confirmable");
        check(attempt.action() == action && !attempt.canConfirm(START + 1),
            "Wrong-action challenge cannot change the selected action or enable confirmation");
        check(!attempt.acceptChallenge(ArmExperimentProtocol.encodeChallenge(action, OTHER_REQUEST, CHALLENGE), NODE,
            START + 1), "Wrong-request challenge rejected");
        check(!attempt.acceptChallenge(examples[2], NODE, START + 1), "Commit is not accepted as a challenge");
        check(attempt.acceptChallenge(examples[1], NODE, START + 2), "Correct challenge accepted");
        check(!attempt.acceptChallenge(examples[1], NODE, START + 3), "Duplicate challenge rejected");
        check(!attempt.acceptResult(examples[3], NODE, START + 3), "Result before confirmation rejected");
        check(attempt.canConfirm(START + 3), "Fresh challenge is confirmable");
        byte[] commit = attempt.commit(START + 3);
        ArmExperimentProtocol.Message decoded = ArmExperimentProtocol.parse(commit, ArmExperimentProtocol.Kind.COMMIT);
        check(decoded != null && decoded.action == action && REQUEST.equals(decoded.requestId)
            && CHALLENGE.equals(decoded.challengeId), "Commit exactly echoes action, request and challenge");
        check(attempt.commit(START + 4) == null && !attempt.canConfirm(START + 4), "Commit cannot replay");
        check(!attempt.acceptResult(examples[3], "second-node", START + 4), "Wrong-source result rejected");
        for (ArmExperimentProtocol.Outcome outcome : ArmExperimentProtocol.Outcome.values()) {
            check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(other, REQUEST, CHALLENGE, outcome), NODE,
                START + 4), "A result for the other action cannot terminate a committed attempt");
        }
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, OTHER_REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REQUESTED), NODE, START + 4), "Wrong-request result rejected");
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, REQUEST, OTHER_CHALLENGE,
            ArmExperimentProtocol.Outcome.REQUESTED), NODE, START + 4), "Wrong-challenge result rejected");
        check(attempt.acceptResult(examples[3], NODE, START + 5), "Correct committed result accepted");
        check(attempt.outcome() == ArmExperimentProtocol.Outcome.REQUESTED
            && attempt.phase() == ArmExperimentProtocol.Phase.FINISHED, "Only requested is recorded");
        check(!attempt.acceptResult(examples[3], NODE, START + 6), "Result cannot replay");

        attempt = ready(action, 5);
        check(attempt.commit(START + 6) != null, "Ready request can commit");
        check(attempt.acceptResult(examples[4], NODE, START + 7)
            && attempt.outcome() == ArmExperimentProtocol.Outcome.REJECTED, "Rejection is a terminal matched result");

        attempt = ready(action, 5);
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(other, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED), NODE, START + 6),
            "Precommit rejection for the other action cannot revoke this challenge");
        check(!attempt.acceptResult(examples[4], "second-node", START + 6),
            "Precommit rejection from another source is ignored");
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, OTHER_REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED), NODE, START + 6),
            "Precommit rejection for another request is ignored");
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, REQUEST, OTHER_CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED), NODE, START + 6),
            "Precommit rejection for another challenge is ignored");
        check(!attempt.acceptResult(examples[3], NODE, START + 6),
            "A precommit REQUESTED result is still ignored");
        check(attempt.canConfirm(START + 6), "Invalid precommit results leave the valid challenge confirmable");
        check(attempt.acceptResult(examples[4], NODE, START + 7),
            "The phone can revoke its matching challenge before confirmation");
        check(attempt.outcome() == ArmExperimentProtocol.Outcome.REJECTED
            && attempt.phase() == ArmExperimentProtocol.Phase.FINISHED,
            "Precommit rejection is terminal");
        check(!attempt.canConfirm(START + 8) && attempt.commit(START + 8) == null,
            "A rejected challenge cannot later commit");
        check(!attempt.acceptResult(examples[4], NODE, START + 8),
            "Precommit rejection cannot replay");

        attempt = ready(action, 5);
        check(!attempt.acceptResult(examples[4], NODE, START + 5 + ArmExperimentProtocol.CONFIRM_TIMEOUT_MS),
            "A precommit rejection after the local challenge deadline is ignored");
        check(attempt.phase() == ArmExperimentProtocol.Phase.EXPIRED && attempt.outcome() == null,
            "An expired challenge stays expired rather than accepting a late outcome");

        attempt = awaiting(action);
        check(!attempt.acceptResult(examples[4], NODE, START + 3),
            "A rejection without an accepted challenge is ignored");

        attempt = awaiting(action);
        check(!attempt.acceptChallenge(examples[1], NODE, START + ArmExperimentProtocol.READINESS_TIMEOUT_MS),
            "Challenge exactly at readiness deadline is too late");
        check(attempt.phase() == ArmExperimentProtocol.Phase.EXPIRED
            && !attempt.isActive(START + 3), "Expiry cannot be revived by an earlier clock value");

        attempt = ready(action, 1_000);
        check(!attempt.canConfirm(START + 1_000 + ArmExperimentProtocol.CONFIRM_TIMEOUT_MS),
            "Confirmation exactly at local challenge deadline is too late");
        check(attempt.commit(START + 1_001) == null, "Expired confirmation cannot be revived");

        attempt = ready(action, 9_000);
        check(attempt.commit(START + 28_999) != null, "A late but bounded confirmation is accepted");
        check(!attempt.acceptResult(examples[3], NODE, START + ArmExperimentProtocol.TOTAL_TIMEOUT_MS),
            "Even a recent commit cannot extend the absolute total lifetime");

        attempt = ready(action, 5);
        check(attempt.commit(START + 6) != null, "Timely commit emitted");
        check(!attempt.acceptResult(examples[3], NODE, START + 6 + ArmExperimentProtocol.RESULT_TIMEOUT_MS),
            "Result exactly at its own deadline is rejected");

        attempt = ready(action, 5);
        attempt.cancel();
        check(!attempt.canConfirm(START + 6) && attempt.commit(START + 6) == null,
            "Cancellation consumes confirmation");
        check(!attempt.acceptResult(examples[3], NODE, START + 7), "Canceled attempt cannot receive a result");
        check(attempt.phase() == ArmExperimentProtocol.Phase.CANCELLED, "Cancellation remains terminal");

        attempt = ready(action, 5);
        check(!attempt.isActive(START + 4) && attempt.phase() == ArmExperimentProtocol.Phase.EXPIRED,
            "A backward local clock expires an attempt instead of extending it");

        ArmExperimentProtocol.Attempt first = new ArmExperimentProtocol.Attempt(action, START);
        ArmExperimentProtocol.Attempt second = new ArmExperimentProtocol.Attempt(action, START);
        check(!first.requestId().equals(second.requestId()), "Production attempts get different random UUIDs");
    }

    private static void checkDeclined(AlarmAction action) {
        AlarmAction other = action == AlarmAction.ARM_STAY ? AlarmAction.DISARM : AlarmAction.ARM_STAY;
        byte[] refusal = new AlarmStateProtocol.Declined(action, REQUEST,
            AlarmStateProtocol.DeclineReason.STATE_CHANGED).encode();
        ArmExperimentProtocol.Attempt attempt = new ArmExperimentProtocol.Attempt(action, REQUEST, START);
        check(!attempt.acceptDeclined(refusal, NODE, START), "An unselected attempt cannot accept a refusal");
        check(attempt.selectTarget(NODE, START + 1), "The refusal fixture selects its phone");
        check(!attempt.acceptDeclined(refusal, NODE, START + 1), "A refusal before PREPARE is ignored");
        check(attempt.phase() == ArmExperimentProtocol.Phase.DISCOVERING && attempt.outcome() == null,
            "A premature refusal cannot change the attempt phase or outcome");
        check(attempt.prepare(START + 2) != null, "A refusal requires one emitted PREPARE");
        check(!attempt.acceptDeclined(refusal, "other-phone", START + 3), "Wrong-source refusal is ignored");
        check(!attempt.acceptDeclined(refusal, null, START + 3), "A refusal without a source is ignored");
        check(!attempt.acceptDeclined(new AlarmStateProtocol.Declined(other, REQUEST,
            AlarmStateProtocol.DeclineReason.STATE_CHANGED).encode(), NODE, START + 3),
            "A refusal for the other action is ignored");
        check(!attempt.acceptDeclined(new AlarmStateProtocol.Declined(action, OTHER_REQUEST,
            AlarmStateProtocol.DeclineReason.STATE_CHANGED).encode(), NODE, START + 3),
            "A refusal for another request is ignored");
        check(!attempt.acceptDeclined(ArmExperimentProtocol.encodePrepare(action, REQUEST), NODE, START + 3),
            "A PREPARE cannot impersonate a refusal");
        check(!attempt.acceptDeclined(null, NODE, START + 3), "A null refusal is ignored");
        check(attempt.phase() == ArmExperimentProtocol.Phase.AWAITING_CHALLENGE && attempt.outcome() == null,
            "Invalid refusals leave the pending request unchanged");
        check(!attempt.canConfirm(START + 3) && attempt.commit(START + 3) == null,
            "No refusal or invalid message can authorize a COMMIT");
        check(attempt.acceptDeclined(refusal, NODE, START + ArmExperimentProtocol.READINESS_TIMEOUT_MS - 1),
            "A matched refusal is accepted up to the readiness deadline");
        check(attempt.phase() == ArmExperimentProtocol.Phase.FINISHED
            && attempt.outcome() == ArmExperimentProtocol.Outcome.REJECTED && attempt.challengeId() == null,
            "Early refusal finishes rejected without creating a challenge");
        check(!attempt.canConfirm(START + 10_000) && attempt.commit(START + 10_000) == null
            && attempt.prepare(START + 10_000) == null, "Refusal cannot authorize a commit or another prepare");
        check(!attempt.acceptChallenge(ArmExperimentProtocol.encodeChallenge(action, REQUEST, CHALLENGE), NODE,
            START + 10_000), "A later challenge cannot revive a refused attempt");
        check(!attempt.acceptDeclined(refusal, NODE, START + 10_000), "Refusal cannot replay");
        check(!attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REQUESTED), NODE, START + 10_000),
            "A late result cannot turn early refusal into an attempted command");

        for (AlarmStateProtocol.DeclineReason reason : AlarmStateProtocol.DeclineReason.values()) {
            attempt = awaiting(action);
            check(attempt.acceptDeclined(new AlarmStateProtocol.Declined(action, REQUEST, reason).encode(), NODE,
                START + 3), "Every recognized matched refusal reason can end readiness");
            check(attempt.commit(START + 4) == null, "No refusal reason permits a COMMIT");
        }

        attempt = awaiting(action);
        check(!attempt.acceptDeclined(refusal, NODE, START + ArmExperimentProtocol.READINESS_TIMEOUT_MS),
            "A refusal exactly at the readiness deadline is rejected");
        check(attempt.phase() == ArmExperimentProtocol.Phase.EXPIRED && attempt.outcome() == null,
            "An expired request cannot acquire a late rejection outcome");
        attempt = awaiting(action);
        check(!attempt.acceptDeclined(refusal, NODE, START + 1), "A backward clock expires the refusal's attempt");
        check(attempt.phase() == ArmExperimentProtocol.Phase.EXPIRED, "Clock expiry remains terminal");
        attempt = awaiting(action); attempt.cancel();
        check(!attempt.acceptDeclined(refusal, NODE, START + 3)
            && attempt.phase() == ArmExperimentProtocol.Phase.CANCELLED && attempt.outcome() == null,
            "A canceled request cannot accept a refusal");

        attempt = ready(action, 5);
        check(!attempt.acceptDeclined(refusal, NODE, START + 6),
            "A challenge-free refusal cannot terminate an already-issued challenge");
        check(attempt.phase() == ArmExperimentProtocol.Phase.CONFIRMABLE && attempt.canConfirm(START + 6),
            "Only the established challenge remains confirmable");
        check(attempt.commit(START + 7) != null, "The original challenge still permits its one explicit commit");
        check(!attempt.acceptDeclined(refusal, NODE, START + 8)
            && attempt.phase() == ArmExperimentProtocol.Phase.AWAITING_RESULT && attempt.outcome() == null,
            "An early-refusal replay cannot classify a committed action as definitely unsent");
        check(attempt.acceptResult(ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REQUESTED), NODE, START + 9),
            "The committed request still accepts only its matched challenged result");
    }

    public static void main(String[] ignored) {
        checks = 0;
        check("Arm Stay".equals(AlarmAction.ARM_STAY.label()), "Arm Stay label is explicit");
        check("Disarm".equals(AlarmAction.DISARM.label()), "Disarm label is explicit");
        check("WATCH ARM STAY".equals(AlarmAction.ARM_STAY.widgetLabel()), "Arm widget label is exact");
        check("WATCH DISARM".equals(AlarmAction.DISARM.widgetLabel()), "Disarm widget label is exact");
        for (AlarmAction action : AlarmAction.values()) { checkAction(action); checkDeclined(action); }
        System.out.println("Alarm experiment protocol: " + checks + " checks passed.");
    }
}
