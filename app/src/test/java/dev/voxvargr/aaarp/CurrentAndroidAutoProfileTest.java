package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public final class CurrentAndroidAutoProfileTest {
    @After
    public void resetTracker() {
        CurrentAndroidAutoProfile.disconnected();
    }

    @Test
    public void connectedProfileIsPublishedAndDisconnectClearsIt() {
        assertNull(CurrentAndroidAutoProfile.detectedProfileId());

        CurrentAndroidAutoProfile.connected("bike");
        assertEquals("bike", CurrentAndroidAutoProfile.detectedProfileId());

        CurrentAndroidAutoProfile.disconnected();
        assertNull(CurrentAndroidAutoProfile.detectedProfileId());
    }

    @Test
    public void missingProfileFallsBackToDefault() {
        CurrentAndroidAutoProfile.connected(null);
        assertEquals(
                ProfileSettings.DEFAULT_PROFILE_ID,
                CurrentAndroidAutoProfile.detectedProfileId()
        );
    }

    @Test
    public void noDetectionUsesDefaultInsteadOfUiSelection() {
        assertEquals(
                ProfileSettings.DEFAULT_PROFILE_ID,
                CurrentAndroidAutoProfile.detectedOrDefaultProfileId()
        );
    }

    @Test
    public void listenersReceiveOnlyActualProfileTransitions() {
        List<String> transitions = new ArrayList<>();
        CurrentAndroidAutoProfile.Listener listener = transitions::add;
        CurrentAndroidAutoProfile.addListener(listener);
        try {
            CurrentAndroidAutoProfile.connected("bike");
            CurrentAndroidAutoProfile.connected("bike");
            CurrentAndroidAutoProfile.connected("truck");
            CurrentAndroidAutoProfile.disconnected();
            CurrentAndroidAutoProfile.disconnected();
        } finally {
            CurrentAndroidAutoProfile.removeListener(listener);
        }

        assertEquals(Arrays.asList("bike", "truck", null), transitions);
    }
}
