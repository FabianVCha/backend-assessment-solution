package com.meridian.payments.intake.store;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory stand-in for the inbox and the outbox.
 *
 * <p>It reproduces the two properties the design leans on, not the SQL: a unique
 * {@code (provider, event_id)} and a single transaction that writes the event and both jobs together.
 * The transaction is simulated by staging into copies and publishing them only after every write
 * would have succeeded, so a failure injected in the middle leaves the store exactly as it was. That
 * is what makes the durability tests meaningful without a database.
 *
 * <p>Not for production: no persistence, no retry counters, no concurrency control beyond one lock.
 */
public final class InMemoryIntakeStore implements IntakeStore {

    private record EventKey(Provider provider, String eventId) {
    }

    private final Object lock = new Object();
    private final Map<EventKey, CanonicalEvent> events = new LinkedHashMap<>();
    private final List<OutboxJob> outbox = new ArrayList<>();
    private boolean failBeforeCommit;
    private boolean failMidTransaction;

    @Override
    public Acceptance accept(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        EventKey key = new EventKey(event.provider(), event.eventId());
        synchronized (lock) {
            if (failBeforeCommit) {
                failBeforeCommit = false;
                throw new StoreUnavailableException("the store is unavailable");
            }
            if (events.containsKey(key)) {
                return Acceptance.DUPLICATE;
            }

            Map<EventKey, CanonicalEvent> stagedEvents = new LinkedHashMap<>(events);
            stagedEvents.put(key, event);
            List<OutboxJob> stagedOutbox = new ArrayList<>(outbox);
            stagedOutbox.add(new OutboxJob(event.provider(), event.eventId(), Destination.SALESFORCE));
            stagedOutbox.add(new OutboxJob(event.provider(), event.eventId(), Destination.CANONICAL_EVENT));

            if (failMidTransaction) {
                failMidTransaction = false;
                throw new StoreUnavailableException("the store failed after the inbox row");
            }

            events.clear();
            events.putAll(stagedEvents);
            outbox.clear();
            outbox.addAll(stagedOutbox);
            return Acceptance.ACCEPTED;
        }
    }

    /** Makes the next accepted write fail before anything is staged. */
    public void failNextAcceptBeforeCommit() {
        synchronized (lock) {
            failBeforeCommit = true;
        }
    }

    /** Makes the next accepted write fail after the inbox row would have been written. */
    public void failNextAcceptMidTransaction() {
        synchronized (lock) {
            failMidTransaction = true;
        }
    }

    public int eventCount() {
        synchronized (lock) {
            return events.size();
        }
    }

    public List<OutboxJob> outboxJobs() {
        synchronized (lock) {
            return List.copyOf(outbox);
        }
    }
}
