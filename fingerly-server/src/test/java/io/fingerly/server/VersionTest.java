package io.fingerly.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What the SDK reports upstream must be what Gradle publishes. */
class VersionTest {

    @Test
    void reportsThePublishedVersion() {
        assertEquals(System.getProperty("fingerly.version"), FingerlyClient.VERSION,
                "FingerlyClient.VERSION drifted from the build version");
    }
}
