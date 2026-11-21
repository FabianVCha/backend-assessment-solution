package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Field-level behaviour of the Quetzal mapper against the published samples. */
class QuetzalCanonicalEventMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-28T14:10:00Z");
    private final QuetzalCanonicalEventMapper mapper = new QuetzalCanonicalEventMapper();

    @Test
    void capturedChargeCarriesTheChargedState() {
        CanonicalEvent event = map(TestSamples.QZ_SUCCEEDED);

        assertEquals("evt_01JDQ3K8M2X7", event.eventId());
        assertEquals(Provider.QUETZAL, event.provider());
        assertEquals("MX", event.country());
        assertEquals("QZ-MX-ch_7Hk2Lm9QpZ", event.paymentKey());
        assertEquals("ch_7Hk2Lm9QpZ", event.providerTransactionId());
        assertEquals("POL-MX-000412785", event.policyNumber());
        assertEquals(125_000L, event.amountMinor(), "Quetzal already sends the minor unit");
        assertEquals("MXN", event.currency(), "the provider sends mxn and the event is ISO upper case");
        assertEquals(2, event.currencyExponent());
        assertEquals("CAPTURED", event.status());
        assertNull(event.refundedAmountMinor());
        assertNull(event.refundedAmountSemantics());
        assertNull(event.refundScope());
        assertEquals(Instant.ofEpochSecond(1_795_888_796L), event.statusChangedAt(), "paid_at");
        assertEquals(Instant.ofEpochSecond(1_795_888_800L), event.occurredAt(), "created");
        assertEquals(RECEIVED_AT, event.receivedAt());
        assertEquals("ch_7Hk2Lm9QpZ", event.correlationId());
        assertEquals(TestSamples.sha256Hex(TestSamples.read(TestSamples.QZ_SUCCEEDED)), event.payloadHash());
    }

    @Test
    void failedChargeIsDatedWithCreatedBecausePaidAtIsNull() {
        CanonicalEvent event = map(TestSamples.QZ_FAILED);

        assertEquals("FAILED", event.status());
        assertEquals(98_000L, event.amountMinor());
        assertEquals("POL-MX-000398110", event.policyNumber());
        assertEquals(Instant.ofEpochSecond(1_795_889_400L), event.statusChangedAt(),
                "a failed charge has no paid_at, so the event creation time is the state time");
    }

    @Test
    void fullRefundComparesTheReportedAmountWithTheCharge() {
        CanonicalEvent event = map(TestSamples.QZ_REFUNDED);

        assertEquals("REFUNDED", event.status());
        assertEquals(125_000L, event.amountMinor(), "the original amount is never replaced by the refund");
        assertEquals(125_000L, event.refundedAmountMinor());
        assertEquals("PROVIDER_REPORTED_UNSPECIFIED", event.refundedAmountSemantics());
        assertEquals("FULL", event.refundScope());
        assertEquals(Instant.ofEpochSecond(1_795_975_195L), event.statusChangedAt(), "refunded_at");
        assertEquals(Instant.ofEpochSecond(1_795_975_200L), event.occurredAt(), "created");
    }

    @Test
    void partialRefundKeepsBothFiguresAndReportsPartial() {
        CanonicalEvent event = mapWithRefundedAmount(50_000L);

        assertEquals("PARTIAL", event.refundScope());
        assertEquals(50_000L, event.refundedAmountMinor());
        assertEquals(125_000L, event.amountMinor());
        assertEquals("PROVIDER_REPORTED_UNSPECIFIED", event.refundedAmountSemantics());
    }

    @Test
    void aRefundLargerThanTheChargeIsCarriedAsReportedAndNotInterpreted() {
        CanonicalEvent event = mapWithRefundedAmount(200_000L);

        assertEquals(200_000L, event.refundedAmountMinor(), "the figure is reported, never corrected");
        assertEquals("UNKNOWN", event.refundScope(),
                "a figure above the charge cannot be called full or partial");
    }

    @Test
    void aRefundEventWithoutAReportedAmountStaysNullAndUnknown() {
        String body = TestSamples.asText(TestSamples.read(TestSamples.QZ_REFUNDED));
        String withoutAmount = body.replace("  \"amount_refunded\": 125000,\n", "");
        assertNotEquals(body, withoutAmount, "the sample must contain the field being removed");

        CanonicalEvent event = map(withoutAmount.getBytes(StandardCharsets.UTF_8));

        assertEquals("REFUNDED", event.status());
        assertNull(event.refundedAmountMinor());
        assertNull(event.refundedAmountSemantics());
        assertEquals("UNKNOWN", event.refundScope(), "null is not a full refund");
    }

    private CanonicalEvent map(String sample) {
        return map(TestSamples.read(sample));
    }

    private CanonicalEvent map(byte[] body) {
        return mapper.map(new WebhookRequest(Provider.QUETZAL, Map.of(), body), RECEIVED_AT);
    }

    private CanonicalEvent mapWithRefundedAmount(long amount) {
        String body = TestSamples.asText(TestSamples.read(TestSamples.QZ_REFUNDED));
        String replaced = body.replace("\"amount_refunded\": 125000", "\"amount_refunded\": " + amount);
        assertNotEquals(body, replaced, "the sample must contain the refunded amount being replaced");
        return map(replaced.getBytes(StandardCharsets.UTF_8));
    }
}
