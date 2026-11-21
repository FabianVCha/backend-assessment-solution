package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Austral to canonical, field by field.
 *
 * <p>The amount is the test that matters most: CLP has no minor unit, so 45,990 must stay 45,990 with
 * exponent 0. A mapper that reused the two-decimal helper would produce 4,599,000 and the schema would
 * accept it, because the schema cannot know which currency was reported. Only this test can.
 */
class AustralCanonicalEventMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AustralCanonicalEventMapper mapper = new AustralCanonicalEventMapper();

    @Test
    void aConfirmedPaymentBecomesACapturedEvent() {
        CanonicalEvent event = map(TestSamples.AU_CONFIRMED);

        assertEquals("aus_ev_93f0c1d2", event.eventId());
        assertEquals(Provider.AUSTRAL, event.provider());
        assertEquals("CL", event.country());
        assertEquals("AU-CL-AUS-P-77120", event.paymentKey());
        assertEquals("AUS-P-77120", event.providerTransactionId());
        assertEquals("POL-CL-000093311", event.policyNumber());
        assertEquals("CAPTURED", event.status());
        assertEquals("CLP", event.currency());
        assertEquals(0, event.currencyExponent());
        assertEquals(45_990L, event.amountMinor(), "CLP has no minor unit: the reported 45990 is the amount");
        assertEquals(Instant.parse("2026-11-28T17:05:40Z"), event.statusChangedAt());
        assertEquals(Instant.parse("2026-11-28T17:05:44Z"), event.occurredAt());
        assertEquals(RECEIVED_AT, event.receivedAt());
        assertEquals("aus_ev_93f0c1d2", event.correlationId());
        assertNull(event.refundedAmountMinor());
        assertNull(event.refundScope());
    }

    @Test
    void aReversalCarriesNoAmountAndSaysUnknownRatherThanFull() {
        CanonicalEvent event = map(TestSamples.AU_REVERSED);

        assertEquals("REFUNDED", event.status());
        assertNull(event.refundedAmountMinor(), "the provider reported no amount");
        assertNull(event.refundedAmountSemantics());
        assertEquals("UNKNOWN", event.refundScope(),
                "a reversal without an amount is not a full refund: nobody said how much came back");
    }

    @Test
    void thePayerBlockNeverReachesTheEvent() {
        String body = map(TestSamples.AU_CONFIRMED).toJson().toString();

        assertFalse(body.contains("Camila"), body);
        assertFalse(body.contains("11.111.111"), body);
        assertFalse(body.contains("camila.rojas"), body);
        assertFalse(body.toLowerCase().contains("rut"), body);
    }

    @Test
    void aRetryOfTheSameBodyIsTheSameEvent() {
        assertEquals(map(TestSamples.AU_CONFIRMED).eventId(), map(TestSamples.AU_CONFIRMED).eventId(),
                "the provider keeps eventId stable across retries, and that is the dedupe key");
    }

    @Test
    void theReversalOfAPaymentIsANewEventForTheSamePayment() {
        CanonicalEvent confirmed = map(TestSamples.AU_CONFIRMED);
        CanonicalEvent reversed = map(TestSamples.AU_REVERSED);

        assertEquals(confirmed.paymentKey(), reversed.paymentKey());
        assertFalse(confirmed.eventId().equals(reversed.eventId()));
        assertTrue(reversed.statusChangedAt().isAfter(confirmed.statusChangedAt()),
                "the projection must be able to order these two without guessing");
    }

    @Test
    void anUnknownEventTypeIsRejected() {
        assertFailure(mutate(TestSamples.AU_CONFIRMED,
                payload -> payload.put("eventType", "PAYMENT_PENDING")),
                CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED);
    }

    @Test
    void aCurrencyThatIsNotTheProvidersIsRejected() {
        assertFailure(mutate(TestSamples.AU_CONFIRMED,
                payload -> payload.with("payment").put("currency", "USD")),
                CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED);
    }

    @Test
    void aPolicyThatIsNotChileanIsRejected() {
        assertFailure(mutate(TestSamples.AU_CONFIRMED,
                payload -> payload.with("payment").put("policyNumber", "POL-MX-000093311")),
                CanonicalMappingException.Failure.POLICY_REFERENCE_UNPARSEABLE);
    }

    @Test
    void aDecimalAmountIsRejectedInsteadOfBeingRounded() {
        assertFailure(mutate(TestSamples.AU_CONFIRMED,
                payload -> payload.with("payment").put("amount", "45990.00")),
                CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED);
    }

    @Test
    void aMissingPaymentIdIsRejected() {
        assertFailure(mutate(TestSamples.AU_CONFIRMED,
                payload -> payload.with("payment").remove("paymentId")),
                CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED);
    }

    @Test
    void aDocumentFromAnotherProviderIsRejected() {
        assertFailure(TestSamples.read(TestSamples.QZ_SUCCEEDED),
                CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED);
    }

    private CanonicalEvent map(String sample) {
        return mapper.map(request(TestSamples.read(sample)), RECEIVED_AT);
    }

    private void assertFailure(byte[] body, CanonicalMappingException.Failure expected) {
        CanonicalMappingException failure = assertThrows(CanonicalMappingException.class,
                () -> mapper.map(request(body), RECEIVED_AT));
        assertEquals(expected, failure.failure());
    }

    private static WebhookRequest request(byte[] body) {
        return new WebhookRequest(Provider.AUSTRAL, Map.of(), body);
    }

    private static byte[] mutate(String sample, Consumer<ObjectNode> change) {
        try {
            ObjectNode payload = (ObjectNode) JSON.readTree(TestSamples.read(sample));
            change.accept(payload);
            return JSON.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("cannot build the mutated sample", e);
        }
    }
}
