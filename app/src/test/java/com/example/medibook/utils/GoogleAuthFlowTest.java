package com.example.medibook.utils;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.medibook.utils.GoogleAuthFlow.Mode.*;
import static com.example.medibook.utils.GoogleAuthFlow.Stage.*;

public class GoogleAuthFlowTest {
    @Test public void explicitSignInBeginsAtChooserAndRejectsDoubleTap() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); assertTrue(flow.start(1, SIGN_IN, null));
        assertEquals(SELECTING, flow.stage()); assertFalse(flow.start(2, SIGN_IN, null)); assertTrue(flow.busy());
    }
    @Test public void signInCannotReplaceAnAlreadySignedInAccount() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); assertFalse(flow.start(1, SIGN_IN, "existing"));
        assertTrue(flow.start(1, SIGN_IN, null)); assertFalse(flow.accepts(1, SELECTING, "unexpected-account"));
    }
    @Test public void linkingRequiresExistingUidAndPasswordProofBeforeChooser() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); assertFalse(flow.start(1, LINK, null));
        assertTrue(flow.start(1, LINK, "patient-a")); assertEquals(REAUTHENTICATING, flow.stage());
        assertFalse(flow.advance(1, SELECTING, EXCHANGING, "patient-a"));
        assertTrue(flow.advance(1, REAUTHENTICATING, SELECTING, "patient-a"));
        assertTrue(flow.advance(1, SELECTING, EXCHANGING, "patient-a"));
    }
    @Test public void accountSwitchInvalidatesEveryLinkingStage() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(1, LINK, "a");
        assertFalse(flow.accepts(1, REAUTHENTICATING, "b")); assertFalse(flow.accepts(1, REAUTHENTICATING, null));
        flow.advance(1, REAUTHENTICATING, SELECTING, "a");
        assertFalse(flow.advance(1, SELECTING, EXCHANGING, "b"));
    }
    @Test public void chooserCancellationRejectsLateCredentialsAndPermitsExplicitRetry() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(1, SIGN_IN, null); flow.finish(false);
        assertFalse(flow.busy()); assertFalse(flow.accepts(1, SELECTING, null));
        assertTrue(flow.start(2, SIGN_IN, null)); assertFalse(flow.accepts(1, SELECTING, null));
    }
    @Test public void missingInvalidCredentialAndNetworkFailureShareTerminalRecovery() {
        for (String failure : new String[]{"missing", "invalid", "network"}) {
            GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(1, SIGN_IN, null); flow.finish(false);
            assertFalse(failure, flow.busy()); assertEquals(FAILED, flow.stage()); assertTrue(flow.start(2, SIGN_IN, null));
        }
    }
    @Test public void cancelledReauthenticationCannotLaunchChooserLater() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(1, LINK, "a"); flow.finish(false);
        assertFalse(flow.advance(1, REAUTHENTICATING, SELECTING, "a"));
    }
    @Test public void successRequiresExchangeAndSeparateSessionVerification() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(1, SIGN_IN, null);
        assertFalse(flow.advance(1, SELECTING, VERIFYING, null));
        assertTrue(flow.advance(1, SELECTING, EXCHANGING, null));
        assertTrue(flow.advance(1, EXCHANGING, VERIFYING, "authenticated"));
        flow.finish(true); assertEquals(SUCCEEDED, flow.stage()); assertFalse(flow.busy());
        assertFalse(flow.accepts(1, VERIFYING, "authenticated"));
    }
    @Test public void logoutAndRotationAbandonmentFenceOldCallbacks() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(4, LINK, "a"); flow.clear();
        assertFalse(flow.accepts(4, REAUTHENTICATING, "a")); assertEquals(IDLE, flow.stage());
        assertTrue(flow.start(5, SIGN_IN, null)); assertFalse(flow.accepts(4, SELECTING, null));
    }
    @Test public void requestIdsCannotBeReusedAfterTerminalFailure() {
        GoogleAuthFlow flow = new GoogleAuthFlow(); flow.start(8, SIGN_IN, null); flow.finish(false);
        assertFalse(flow.start(8, SIGN_IN, null)); assertFalse(flow.start(7, SIGN_IN, null)); assertTrue(flow.start(9, SIGN_IN, null));
    }
}
