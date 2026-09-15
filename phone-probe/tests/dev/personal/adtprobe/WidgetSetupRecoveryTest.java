package dev.personal.adtprobe;

import org.junit.Test;
import static org.junit.Assert.*;

/** Additional ordering/expiry cases; no Android runtime or provider is involved. */
public final class WidgetSetupRecoveryTest {
    private static final long ELAPSED = 100_000, WALL = 1_000_000;

    private WidgetSetupState replacement(int newId) {
        WidgetSetupState state = new WidgetSetupState();
        state.owned.add(7); state.active = 7;
        state.begin(newId, "local-test-flow", ELAPSED, WALL);
        return state;
    }

    @Test public void configuredResultThatExpiresBeforeCommitCannotReplaceActiveWidget() {
        WidgetSetupState state = replacement(9);
        state.boundDirectly();
        int request = state.expect(WidgetSetupState.CONFIGURE);
        assertTrue(state.accept(request, 9, ELAPSED + 1, WALL + 1));
        try {
            state.commit(ELAPSED + WidgetSetupState.MAX_AGE_MS + 1, WALL + 2);
            fail("A delayed commit must not install an expired replacement");
        } catch (IllegalStateException expected) { }
        assertEquals(7, state.active);
        assertEquals(9, state.cancel());
        assertTrue(state.mayDelete(9));
        assertFalse(state.mayDelete(7));
    }

    @Test public void oldResultCannotCompleteNewSetupAfterCancellation() {
        WidgetSetupState state = replacement(9);
        int oldRequest = state.expect(WidgetSetupState.BIND);
        assertEquals(9, state.cancel());
        state.begin(10, "replacement-test-flow", ELAPSED + 10, WALL + 10);
        int newRequest = state.expect(WidgetSetupState.BIND);
        assertFalse(state.accept(oldRequest, 9, ELAPSED + 20, WALL + 20));
        assertFalse(state.accept(newRequest, 9, ELAPSED + 20, WALL + 20));
        assertEquals(10, state.pending);
        assertEquals(7, state.active);
        assertTrue(state.accept(newRequest, 10, ELAPSED + 20, WALL + 20));
    }

    @Test public void overlappingBeginPreservesOriginalFlowForCleanup() {
        WidgetSetupState state = replacement(9);
        int request = state.expect(WidgetSetupState.BIND);
        try {
            state.begin(10, "overlap-test-flow", ELAPSED + 1, WALL + 1);
            fail("A second allocation cannot replace an in-flight setup");
        } catch (IllegalStateException expected) { }
        assertEquals(9, state.pending);
        assertEquals(request, state.request);
        assertFalse(state.owned.contains(10));
        assertTrue(state.accept(request, 9, ELAPSED + 2, WALL + 2));
    }

    @Test public void repeatedCancelCannotMakeTheActiveWidgetDeletable() {
        WidgetSetupState state = replacement(9);
        assertEquals(9, state.cancel());
        assertEquals(-1, state.cancel());
        assertEquals(7, state.active);
        assertFalse(state.mayDelete(7));
        assertTrue(state.mayDelete(9));
    }
}
