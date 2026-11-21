package com.meridian.payments.intake.ops;

import com.meridian.payments.intake.salesforce.SalesforceClient;
import com.meridian.payments.intake.salesforce.SimulatedSalesforce;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.StoreUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceIT extends ReplayPostgresFixture {

    @Test
    void aDeadJobIsRequeuedWithoutChangingTheEventOrOtherDestination() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        relay(new SimulatedSalesforce(SalesforceClient.Result.INVALID_FIELD)).drain();
        execute("UPDATE outbox_job SET attempts = 4 WHERE destination = 'SALESFORCE'");
        assertEquals("DEAD/INVALID_FIELD/4", job("old", Destination.SALESFORCE));
        assertEquals("REQUEUED", replay("old", "Policy corrected", "r-1").name());

        assertEquals("PENDING/REPLAY/0", job("old", Destination.SALESFORCE));
        assertEquals("PENDING/-/0", job("old", Destination.CANONICAL_EVENT));
        assertEquals("1", scalar("SELECT count(*) FROM payment_event"));
        assertEquals("2", scalar("SELECT count(*) FROM outbox_job"));
        assertEquals("true", scalar("""
                SELECT (next_attempt_at BETWEEN now() - interval '1 minute' AND now())::text
                FROM outbox_job WHERE destination = 'SALESFORCE'
                """));
    }

    @Test
    void missingOrBlankReasonIsRejectedAndAuditedWithoutAReset() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);

        assertEquals("REASON_REQUIRED", replay("old", null, "r-1").name());
        assertEquals("REASON_REQUIRED", replay("old", "  ", "r-2").name());

        assertEquals("PENDING/-/0", job("old", Destination.SALESFORCE));
        assertEquals("2", scalar("SELECT count(*) FROM replay_command WHERE outcome = 'REASON_REQUIRED'"));
    }

    @Test
    void theSameKeyReturnsTheFirstOutcomeWithoutResettingAgain() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        assertEquals("REQUEUED", replay("old", "First justification", "r-1").name());
        assertEquals(1, relay(new SimulatedSalesforce()).drain());

        assertEquals("REQUEUED", replay("old", "Different text on retry", "r-1").name());

        assertEquals("PUBLISHED/APPLIED/0", job("old", Destination.SALESFORCE));
        assertEquals("1", scalar("SELECT count(*) FROM replay_command"));
        assertEquals("First justification", scalar("SELECT reason FROM replay_command"));
    }

    @Test
    void aKeyCannotReportSuccessForAnotherDestination() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            assertEquals(0, runCli(stream, "old", "SALESFORCE", "r-1"));
            assertEquals(1, runCli(stream, "old", "CANONICAL_EVENT", "r-1"));
            assertEquals(0, runCli(stream, "old", "SALESFORCE", "r-1"));
        }
        assertEquals(List.of("REQUEUED", "KEY_TARGET_CONFLICT", "REQUEUED"),
                output.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals("PENDING/-/0", job("old", Destination.CANONICAL_EVENT));
        assertEquals("1", scalar("SELECT count(*) FROM replay_command"));
        assertEquals("SALESFORCE/REQUEUED", scalar("SELECT destination || '/' || outcome FROM replay_command"));
    }

    @Test
    void concurrentIdenticalCommandsResetOnce() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        execute("""
                CREATE TABLE replay_test_reset (outbox_id bigint);
                CREATE FUNCTION replay_test_count_reset() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN INSERT INTO replay_test_reset VALUES (NEW.outbox_id); RETURN NEW; END $$;
                CREATE TRIGGER replay_test_reset AFTER UPDATE ON outbox_job
                FOR EACH ROW WHEN (NEW.outcome_code = 'REPLAY') EXECUTE FUNCTION replay_test_count_reset()
                """);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<ReplayService.Outcome> command = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return replay("old", "Policy corrected", "r-1");
            };
            var first = workers.submit(command);
            var second = workers.submit(command);
            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(bothReady);
            assertEquals("REQUEUED", first.get(10, TimeUnit.SECONDS).name());
            assertEquals("REQUEUED", second.get(10, TimeUnit.SECONDS).name());
            assertEquals("1", scalar("SELECT count(*) FROM replay_test_reset"));
            assertEquals("1", scalar("SELECT count(*) FROM replay_command"));
            assertEquals("PENDING/REPLAY/0", job("old", Destination.SALESFORCE));
        } finally {
            execute("""
                    DROP TRIGGER replay_test_reset ON outbox_job;
                    DROP FUNCTION replay_test_count_reset();
                    DROP TABLE replay_test_reset
                    """);
        }
    }

    @Test
    void failingToRecordTheOutcomeRollsBackTheResetAndLeavesTheKeyReusable() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        execute("""
                UPDATE outbox_job SET state = 'DEAD', outcome_code = 'INVALID_FIELD', attempts = 4
                WHERE destination = 'SALESFORCE';
                CREATE FUNCTION replay_test_reject_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit update failure'; END $$;
                CREATE TRIGGER replay_test_reject BEFORE UPDATE ON replay_command
                FOR EACH ROW EXECUTE FUNCTION replay_test_reject_outcome()
                """);
        try {
            assertThrows(StoreUnavailableException.class, () -> replay("old", "Policy corrected", "r-1"));
            assertEquals("DEAD/INVALID_FIELD/4", job("old", Destination.SALESFORCE));
            assertEquals("PENDING/-/0", job("old", Destination.CANONICAL_EVENT));
            assertEquals("0", scalar("SELECT count(*) FROM replay_command"));
            assertEquals("1", scalar("SELECT count(*) FROM payment_event"));
        } finally {
            execute("DROP TRIGGER replay_test_reject ON replay_command; DROP FUNCTION replay_test_reject_outcome()");
        }
        assertEquals("REQUEUED", replay("old", "Policy corrected", "r-1").name());
        assertEquals("PENDING/REPLAY/0", job("old", Destination.SALESFORCE));
    }

    @Test
    void aClaimedJobIsNotResetEvenWhenItsLeaseHasExpired() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        var claim = salesforceStore.claimDue(1, "another-worker", Duration.ofSeconds(30)).getFirst();
        execute("UPDATE outbox_job SET claim_expires_at = now() - interval '1 second' WHERE state = 'CLAIMED'");

        assertEquals("JOB_CLAIMED", replay("old", "Investigate job", "r-1").name());

        assertEquals("CLAIMED/-/0", job("old", Destination.SALESFORCE));
        assertEquals(claim.claimToken(), scalar("SELECT claim_token::text FROM outbox_job WHERE state = 'CLAIMED'"));
        assertEquals("JOB_CLAIMED", scalar("SELECT outcome FROM replay_command"));
    }

    @Test
    void replayOfAnOlderEventUsesTheNormalOrderingCheck() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        accept("new", "PAY-1", "POL-CO-000000001", "REFUNDED", Instant.EPOCH.plusSeconds(1));
        SimulatedSalesforce client = new SimulatedSalesforce();
        var relay = relay(client);
        assertEquals(1, relay.drain());

        assertEquals("REQUEUED", replay("old", "Confirm ordering", "r-1").name());
        assertEquals("PENDING/REPLAY/0", job("old", Destination.SALESFORCE));
        assertEquals(0, relay.drain());

        assertEquals(1, client.writeCount());
        assertEquals("PUBLISHED/STALE_SKIPPED/0", job("old", Destination.SALESFORCE));
        assertEquals("REFUNDED", salesforceStore.projection("AN-CO-PAY-1").remoteAppliedStatus());
    }

    @Test
    void cliPrintsARejectionForAnUnknownDestinationOrEventAndCanReplayCanonical() throws Exception {
        accept("old", "PAY-1", "POL-CO-000000001", "CAPTURED", Instant.EPOCH);
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            assertEquals(1, runCli(stream, "old", "UNKNOWN", "r-1"));
            assertEquals(1, runCli(stream, "missing", "SALESFORCE", "r-2"));
            assertEquals(0, runCli(stream, "old", "CANONICAL_EVENT", "r-3"));
        }
        assertEquals(List.of("DESTINATION_UNKNOWN", "EVENT_NOT_FOUND", "REQUEUED"),
                output.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals("PENDING/-/0", job("old", Destination.SALESFORCE));
        assertEquals("PENDING/REPLAY/0", job("old", Destination.CANONICAL_EVENT));
        assertNull(scalar("SELECT remote_applied_status FROM payment_state"));
        assertNotNull(scalar("SELECT requested_at::text FROM replay_command WHERE idempotency_key = 'r-3'"));
    }

    private static int runCli(PrintStream output, String eventId, String destination, String key) {
        return ReplayCli.run(new String[]{"ANDINA", eventId, destination, "Operator justification", "ops-test", key},
                jdbcEnvironment(), output, output);
    }
}
