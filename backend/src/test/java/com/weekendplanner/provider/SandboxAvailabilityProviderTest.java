package com.weekendplanner.provider;

import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.mock.MockPoiDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxAvailabilityProviderTest {

    @Test
    void openPublicSpaceDoesNotHaveSyntheticQueue() {
        SandboxAvailabilityProvider provider = new SandboxAvailabilityProvider(new MockPoiDatabase());

        CheckResponse response = provider.checkAvailability("P006", "13:00", 3);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.queueTimeMinutes()).isZero();
        assertThat(response.needPreOrder()).isFalse();
    }
}
