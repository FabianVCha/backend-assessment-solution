package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Field-level behaviour of the Andina mapper against the published samples. */
class AndinaCanonicalEventMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");
    private final AndinaCanonicalEventMapper mapper = new AndinaCanonicalEventMapper();

    @Test
    void pendingTransactionBecomesPending() {
        CanonicalEvent event = map(TestSamples.AN_PENDIENTE);

        assertEquals("PENDING", event.status());
        assertEquals("TRX-CO-5521907", event.providerTransactionId());
        assertEquals("AN-CO-TRX-CO-5521907", event.paymentKey());
        assertEquals("POL-CO-000187342", event.policyNumber());
        assertEquals(Provider.ANDINA, event.provider());
        assertEquals("CO", event.country());
        assertEquals("COP", event.currency());
        assertEquals(2, event.currencyExponent());
        assertNull(event.refundedAmountMinor());
        assertNull(event.refundedAmountSemantics());
        assertNull(event.refundScope());
    }

    @Test
    void majorUnitsBecomeCentsWithoutPassingThroughADouble() {
        CanonicalEvent event = map(TestSamples.AN_APROBADA);

        assertEquals(18_500_000L, event.amountMinor(),
                "valor is \"185000.00\" in major units, so the event carries COP 18,500,000 in cents");
        assertEquals("CAPTURED", event.status());
    }

    @Test
    void offsetsBecomeUtcInstants() {
        CanonicalEvent event = map(TestSamples.AN_APROBADA);

        assertEquals(Instant.parse("2026-11-28T19:03:09Z"), event.statusChangedAt(),
                "fecha_transaccion is 14:03:09-05:00");
        assertEquals(Instant.parse("2026-11-28T19:03:11Z"), event.occurredAt(),
                "fecha_notificacion is 14:03:11-05:00");
    }

    @Test
    void aRetryProducesTheSameEventIdBecauseThePairIsStable() {
        CanonicalEvent first = map(TestSamples.AN_APROBADA);
        CanonicalEvent retry = map(TestSamples.AN_REINTENTO);

        assertEquals(first.eventId(), retry.eventId(),
                "a new notificacion_id on the same state is the same business event");
        assertNotEquals(first.payloadHash(), retry.payloadHash(),
                "the bytes differ, so the hash differs even though the event does not");
        assertEquals(first.statusChangedAt(), retry.statusChangedAt());
        assertNotEquals(TestSamples.sha256Hex(TestSamples.read(TestSamples.AN_APROBADA)),
                TestSamples.sha256Hex(TestSamples.read(TestSamples.AN_REINTENTO)),
                "the two samples must really be different documents");
    }

    @Test
    void differentStatesOfTheSameTransactionAreDifferentEvents() {
        CanonicalEvent pending = map(TestSamples.AN_PENDIENTE);
        CanonicalEvent captured = map(TestSamples.AN_APROBADA);
        CanonicalEvent reversed = map(TestSamples.AN_REVERSADA);

        assertNotEquals(pending.eventId(), captured.eventId());
        assertNotEquals(captured.eventId(), reversed.eventId());
    }

    @Test
    void aReversalCarriesNoAmountAndAnUnknownScope() {
        CanonicalEvent event = map(TestSamples.AN_REVERSADA);

        assertEquals("REFUNDED", event.status());
        assertEquals(18_500_000L, event.amountMinor(), "the original payment amount is kept");
        assertNull(event.refundedAmountMinor(), "the provider reports no refunded amount");
        assertNull(event.refundedAmountSemantics());
        assertEquals("UNKNOWN", event.refundScope(), "a reversal is not a stated full refund");
        assertEquals(Instant.parse("2026-12-03T14:15:40Z"), event.statusChangedAt());
        assertEquals(Instant.parse("2026-12-03T14:15:42Z"), event.occurredAt());
    }

    @Test
    void theEventIdIsADeterministicHashOfThePair() {
        CanonicalEvent event = map(TestSamples.AN_APROBADA);

        assertEquals(TestSamples.sha256Hex("TRX-CO-5521907|APROBADA".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                event.eventId());
        assertEquals(64, event.eventId().length());
    }

    private CanonicalEvent map(String sample) {
        return mapper.map(new WebhookRequest(Provider.ANDINA, Map.of(), TestSamples.read(sample)), RECEIVED_AT);
    }
}
