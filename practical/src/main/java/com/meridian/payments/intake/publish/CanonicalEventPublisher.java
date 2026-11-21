package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.OutboxStore;
import com.meridian.payments.intake.store.PendingJob;

import java.util.List;
import java.util.Objects;

/**
 * Drains the {@code CANONICAL_EVENT} outbox into the topic (FR6).
 *
 * <p>Publication is at-least-once by construction: a job is marked published only after the broker
 * acknowledged it, so a lost acknowledgement leaves it pending and the next poll publishes the same
 * event again. The {@code MessageId} is derived from the event identity, so the duplicate carries the
 * same id and a consumer's own deduplication is enough.
 *
 * <p>The publisher touches that one destination only. The Salesforce job is drained by a separate
 * relay, so a broker outage cannot delay Salesforce and a Salesforce outage cannot delay publication.
 */
public final class CanonicalEventPublisher {

    private final OutboxStore store;
    private final Broker broker;
    private final int batchSize;

    public CanonicalEventPublisher(OutboxStore store, Broker broker, int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        if (batchSize < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        this.batchSize = batchSize;
    }

    /**
     * Publishes one batch and returns how many messages the broker acknowledged.
     *
     * <p>A failed job stays pending with its attempt count increased. The method never throws for a
     * broker problem: the outbox is the record, and the next poll retries.
     */
    public int publishPending() {
        List<PendingJob> batch = store.pending(Destination.CANONICAL_EVENT, batchSize);
        int published = 0;
        for (PendingJob pending : batch) {
            try {
                broker.publish(new CanonicalMessage(
                        MessageIds.of(pending.event().provider(), pending.event().eventId()),
                        pending.event().correlationId(),
                        pending.event().toJson().toString()));
                store.published(pending);
                published++;
            } catch (BrokerUnavailableException e) {
                store.failed(pending, e.getMessage());
            }
        }
        return published;
    }
}
