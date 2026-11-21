package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The document survives a trip through storage.
 *
 * <p>The publisher sends what the store read back, so a field that does not survive serialisation is a
 * field a consumer never receives. These cases use the provider samples, not a hand-built event: the
 * first version of the round trip used a fixture with every field set, and the real Quetzal sample —
 * whose {@code refunded_amount_semantics} is null — failed in the running container instead.
 */
class CanonicalEventsTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");

    @Test
    void aCapturedPaymentWithNoRefundRoundTrips() {
        CanonicalEvent mapped = new QuetzalCanonicalEventMapper().map(
                new WebhookRequest(Provider.QUETZAL, Map.of(), TestSamples.read(TestSamples.QZ_SUCCEEDED)),
                RECEIVED_AT);

        CanonicalEvent readBack = CanonicalEvents.fromJson(mapped.toJson());

        assertEquals(mapped, readBack);
        assertNull(readBack.refundedAmountSemantics(),
                "a payment nobody refunded has no semantics to report, and that null is the fact");
    }

    @Test
    void aRefundedPaymentRoundTrips() {
        CanonicalEvent mapped = new QuetzalCanonicalEventMapper().map(
                new WebhookRequest(Provider.QUETZAL, Map.of(), TestSamples.read(TestSamples.QZ_REFUNDED)),
                RECEIVED_AT);

        assertEquals(mapped, CanonicalEvents.fromJson(mapped.toJson()));
    }

    @Test
    void aColombianReversalRoundTrips() {
        CanonicalEvent mapped = new AndinaCanonicalEventMapper().map(
                new WebhookRequest(Provider.ANDINA, Map.of(), TestSamples.read(TestSamples.AN_REVERSADA)),
                RECEIVED_AT);

        assertEquals(mapped, CanonicalEvents.fromJson(mapped.toJson()));
    }
}
