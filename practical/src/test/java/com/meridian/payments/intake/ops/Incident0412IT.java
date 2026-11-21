package com.meridian.payments.intake.ops;

import com.meridian.payments.intake.salesforce.SalesforceClient;
import com.meridian.payments.intake.salesforce.SimulatedSalesforce;
import com.meridian.payments.intake.store.Destination;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The incident's symptoms and operator response, without the v1 queues or a real Salesforce org. */
class Incident0412IT extends ReplayPostgresFixture {

    @Test
    void invalidPolicyIsQueryableAndRecoveryCannotOverwriteTheNewerStatus() throws Exception {
        String affected = "TRX-CO-5603318";
        String key = "AN-CO-" + affected;
        String badPolicy = "POL-CO-000215560";
        String fixedPolicy = "POL-CO-000198004";
        Instant time = Instant.parse("2026-11-29T04:41:07Z");
        SimulatedSalesforce client = new SimulatedSalesforce()
                .failOnce(key, SalesforceClient.Result.INVALID_FIELD)
                .failOnce(key, SalesforceClient.Result.INVALID_FIELD)
                .failOnce(key, SalesforceClient.Result.INVALID_FIELD);
        var relay = relay(client);

        // Drain between distinct status facts. If both were queued first, the older invalid fact
        // would be skipped by design, and the test would never observe both terminal failures.
        accept("affected-pending", affected, badPolicy, "PENDING", time);
        accept("unrelated-1", "TRX-CO-5603467", fixedPolicy, "CAPTURED", time);
        assertEquals(1, relay.drain());
        accept("affected-capture", affected, badPolicy, "CAPTURED", time.plusSeconds(1));
        accept("unrelated-2", "TRX-CO-5611042", fixedPolicy, "CAPTURED", time.plusSeconds(1));
        assertEquals(1, relay.drain());
        accept("affected-failure", affected, badPolicy, "FAILED", time.plusSeconds(2));
        assertEquals(0, relay.drain());

        assertEquals("affected-capture,affected-failure,affected-pending", scalar("""
                SELECT string_agg(e.event_id, ',' ORDER BY e.event_id)
                FROM outbox_job o JOIN payment_event e USING (intake_id)
                WHERE e.payment_key = ? AND o.destination = 'SALESFORCE'
                  AND o.state = 'DEAD' AND o.outcome_code = 'INVALID_FIELD'
                """, key));
        assertEquals("PUBLISHED/APPLIED/0", job("unrelated-1", Destination.SALESFORCE));
        assertEquals("PUBLISHED/APPLIED/0", job("unrelated-2", Destination.SALESFORCE));
        assertNull(salesforceStore.projection(key).remoteAppliedStatus());
        assertEquals(List.of(badPolicy, badPolicy, badPolicy), client.writes().stream()
                .filter(write -> write.paymentKey().equals(key)).map(SalesforceClient.PaymentWrite::policyNumber)
                .toList());

        accept("affected-corrected", affected, fixedPolicy, "REFUNDED", time.plusSeconds(3));
        assertEquals(1, relay.drain());
        assertEquals("PUBLISHED/APPLIED/0", job("affected-corrected", Destination.SALESFORCE));
        assertEquals(fixedPolicy, client.writes().getLast().policyNumber());
        assertEquals("REFUNDED", client.writes().getLast().status());

        String reason = "INC-0412: verify recovery after policy-reference correction";
        assertEquals("REQUEUED", replay("affected-capture", reason, "inc-0412").name());
        assertEquals("PENDING/REPLAY/0", job("affected-capture", Destination.SALESFORCE));
        int callsBeforeReplay = client.writeCount();
        assertEquals(0, relay.drain());

        assertEquals("PUBLISHED/STALE_SKIPPED/0", job("affected-capture", Destination.SALESFORCE));
        assertEquals("DEAD/INVALID_FIELD/0", job("affected-failure", Destination.SALESFORCE));
        assertEquals(callsBeforeReplay, client.writeCount());
        assertEquals("REFUNDED", salesforceStore.projection(key).remoteAppliedStatus());
        assertEquals("ops-test/ANDINA/affected-capture/SALESFORCE/REQUEUED/" + reason, scalar("""
                SELECT requester || '/' || provider || '/' || event_id || '/' || destination || '/' || outcome
                    || '/' || reason FROM replay_command WHERE idempotency_key = 'inc-0412'
                """));
    }
}
