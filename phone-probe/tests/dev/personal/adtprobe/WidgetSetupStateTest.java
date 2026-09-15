package dev.personal.adtprobe;

/** Run with plain Java; exercises identity/expiry/ownership without a phone or widget provider. */
public final class WidgetSetupStateTest {
    private static final long START = 100_000;
    private static final long WALL = 1_000_000;
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static WidgetSetupState replacement() {
        WidgetSetupState value = new WidgetSetupState();
        value.owned.add(7); value.active = 7;
        value.begin(9, "test-only-flow", START, WALL);
        return value;
    }
    private static void mustRejectCommit(WidgetSetupState value) {
        boolean rejected = false;
        try { value.commit(START + 200, WALL + 200); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Unconfigured state must not commit");
    }

    public static void main(String[] args) {
        WidgetSetupState value = replacement();
        int bind = value.expect(WidgetSetupState.BIND);
        check(!value.accept(bind, 8, START + 1, WALL + 1), "Foreign returned ID must be rejected");
        check(!value.accept(bind + 1, 9, START + 1, WALL + 1), "Wrong request must be rejected");
        check(value.active == 7 && value.pending == 9, "Invalid result must preserve old and pending allocation");
        mustRejectCommit(value);
        check(value.accept(bind, 9, START + 10, WALL + 10), "Matching bind must advance");
        check(!value.accept(bind, 9, START + 11, WALL + 11), "Duplicate bind result must not replay");
        mustRejectCommit(value);
        int configure = value.expect(WidgetSetupState.CONFIGURE);
        check(configure != bind, "Bind and configure use distinct request identities");
        check(!value.accept(bind, 9, START + 20, WALL + 20), "Old bind result cannot satisfy configuration");
        check(value.accept(configure, 9, START + 20, WALL + 20), "Matching configuration must advance");
        check(!value.accept(configure, 9, START + 21, WALL + 21), "Duplicate configuration must not replay");
        check(value.commit(START + 30, WALL + 30) == 7, "Replacement must return only old allocation for cleanup");
        check(value.active == 9 && value.pending == -1 && value.token.isEmpty(), "Commit consumes pending flow");
        check(value.mayDelete(7) && !value.mayDelete(9) && !value.mayDelete(800), "Cleanup is restricted to own unused IDs");

        value = replacement();
        check(!value.mayDelete(7) && !value.mayDelete(9), "Neither active nor pending allocation may be deleted");
        check(value.cancel() == 9 && value.active == 7, "Cancel preserves the existing widget");
        check(value.mayDelete(9) && !value.mayDelete(7), "Only canceled own allocation becomes removable");
        check(!value.fresh(START, WALL), "Canceled flow cannot become fresh again");

        value = replacement(); bind = value.expect(WidgetSetupState.BIND);
        check(!value.accept(bind, 9, START + WidgetSetupState.MAX_AGE_MS + 1, WALL + 1), "Elapsed expiry must reject");
        check(!value.accept(bind, 9, START + 1, WALL + WidgetSetupState.MAX_AGE_MS + 1), "Wall expiry must reject");
        check(!value.accept(bind, 9, START - 1, WALL + 1), "Elapsed clock reset must reject");
        check(!value.accept(bind, 9, START + 1, WALL - 1), "Backward wall clock must reject");
        value.owned.remove(9);
        check(!value.accept(bind, 9, START + 1, WALL + 1), "Lost ownership must reject");

        value = replacement(); value.boundDirectly();
        mustRejectCommit(value);
        configure = value.expect(WidgetSetupState.CONFIGURE);
        check(value.accept(configure, 9, START + 1, WALL + 1), "Prior bind permission still requires config result");
        check(value.commit(START + 2, WALL + 2) == 7, "Direct bind path commits only after configuration");
        System.out.println("Widget setup state: " + checks + " checks passed.");
    }
}
