package com.meridian.payments.intake.store;

import com.meridian.payments.intake.MutableClock;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two properties the design leans on, without a database: a unique event identity and one
 * transaction that writes the event with both jobs.
 */
class InMemoryIntakeStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-28T14:05:00Z");

    private final InMemoryIntakeStore store = new InMemoryIntakeStore();

    @Test
    void theFirstAcceptanceWritesTheEventAndOneJobPerDestination() {
        assertEquals(Acceptance.ACCEPTED, store.accept(quetzal("evt_01")));

        assertEquals(1, store.eventCount());
        assertEquals(List.of(
                        new OutboxJob(Provider.QUETZAL, "evt_01", Destination.SALESFORCE),
                        new OutboxJob(Provider.QUETZAL, "evt_01", Destination.CANONICAL_EVENT)),
                store.outboxJobs());
    }

    @Test
    void aDuplicateIsNotAnErrorAndAddsNoJob() {
        store.accept(quetzal("evt_01"));

        assertEquals(Acceptance.DUPLICATE, store.accept(quetzal("evt_01")));

        assertEquals(1, store.eventCount(), "a duplicate must not create a second event");
        assertEquals(2, store.outboxJobs().size(), "a duplicate must not owe new work");
    }

    @Test
    void theSameEventIdFromAnotherProviderIsADifferentEvent() {
        store.accept(quetzal("shared-id"));
        store.accept(andina("shared-id"));

        assertEquals(2, store.eventCount(),
                "a provider event id is only unique inside its own provider");
        assertEquals(4, store.outboxJobs().size());
    }

    @Test
    void aStoreThatFailsBeforeCommitLeavesNothingBehindAndAcceptsTheRetry() {
        store.failNextAcceptBeforeCommit();

        assertThrows(StoreUnavailableException.class, () -> store.accept(quetzal("evt_01")));

        assertEquals(0, store.eventCount());
        assertEquals(List.of(), store.outboxJobs());

        assertEquals(Acceptance.ACCEPTED, store.accept(quetzal("evt_01")));
        assertEquals(1, store.eventCount());
        assertEquals(2, store.outboxJobs().size());
    }

    @Test
    void aStoreThatFailsMidTransactionCommitsNothing() {
        store.failNextAcceptMidTransaction();

        assertThrows(StoreUnavailableException.class, () -> store.accept(quetzal("evt_01")));

        assertEquals(0, store.eventCount(), "the inbox row alone must never survive");
        assertEquals(List.of(), store.outboxJobs(), "an event without its jobs must never be visible");

        store.accept(quetzal("evt_01"));
        assertEquals(1, store.eventCount());
        assertEquals(2, store.outboxJobs().size());
    }

    @Test
    void aFailedAttemptIsWithheldUntilItsNextAttemptAndTheWaitGrows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-28T14:05:00Z"));
        InMemoryIntakeStore scheduled = new InMemoryIntakeStore(clock);
        scheduled.accept(quetzal("evt_01"));
        OutboxJob job = new OutboxJob(Provider.QUETZAL, "evt_01", Destination.CANONICAL_EVENT);
        PendingJob first = scheduled.pending(Destination.CANONICAL_EVENT, 10).get(0);

        scheduled.failed(first, "the broker is down");

        assertEquals(1, scheduled.attempts(job));
        assertEquals(List.of(), scheduled.pending(Destination.CANONICAL_EVENT, 10),
                "a failed job is not due again immediately");
        assertEquals(clock.instant().plusSeconds(1), scheduled.nextAttemptAt(job));

        clock.advance(Duration.ofSeconds(1));

        PendingJob second = scheduled.pending(Destination.CANONICAL_EVENT, 10).get(0);
        assertEquals(first.job(), second.job());

        scheduled.failed(second, "still down");

        assertEquals(2, scheduled.attempts(job));
        assertEquals(clock.instant().plusSeconds(2), scheduled.nextAttemptAt(job),
                "the wait doubles with each attempt");
    }

    private static CanonicalEvent quetzal(String eventId) {
        return event(eventId, Provider.QUETZAL, "MX", "MXN", "POL-MX-000412785");
    }

    private static CanonicalEvent andina(String eventId) {
        return event(eventId, Provider.ANDINA, "CO", "COP", "POL-CO-000187342");
    }

    private static CanonicalEvent event(
            String eventId, Provider provider, String country, String currency, String policyNumber) {
        return new CanonicalEvent(
                eventId,
                provider,
                country,
                "KEY-" + eventId,
                "TX-" + eventId,
                policyNumber,
                125_000L,
                currency,
                2,
                "CAPTURED",
                null,
                null,
                null,
                Instant.parse("2026-11-28T19:00:00Z"),
                Instant.parse("2026-11-28T19:00:00Z"),
                NOW,
                "TX-" + eventId,
                "0".repeat(64));
    }
}
