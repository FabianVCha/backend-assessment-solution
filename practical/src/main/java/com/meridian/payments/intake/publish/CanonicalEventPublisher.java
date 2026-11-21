package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.store.ClaimLostException;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.OutboxStore;
import com.meridian.payments.intake.store.PendingJob;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Drains the {@code CANONICAL_EVENT} outbox into the topic (FR6).
 *
 * <p>Publication is at-least-once by construction: a job is marked published only after the broker
 * acknowledged it, so a lost acknowledgement leaves it pending and the next poll publishes the same
 * event again. The {@code MessageId} is derived from the event identity, so the duplicate carries the
 * same id and a consumer's own deduplication is enough.
 *
 * <p>The batch is claimed, not read. Two replicas running this class take different jobs, because the
 * claim is the database's decision; a replica that dies holding a lease loses it when it expires and the
 * next poll takes the work. That is why {@link #publishPending} also handles a lost claim: an attempt
 * that outlived its lease must not mark someone else's job done.
 *
 * <p>The publisher touches that one destination only. The Salesforce job is drained by a separate
 * relay, so a broker outage cannot delay Salesforce and a Salesforce outage cannot delay publication.
 */
public final class CanonicalEventPublisher {

    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.publish");

    /**
     * How long one batch is owned.
     *
     * <p>Long enough for a broker call and a commit, short enough that a crash does not park a payment:
     * when the lease expires the row is due again and another worker, or this one after a restart,
     * takes it.
     */
    public static final Duration LEASE = Duration.ofSeconds(30);

    private final OutboxStore store;
    private final Broker broker;
    private final int batchSize;
    private final String owner;

    public CanonicalEventPublisher(OutboxStore store, Broker broker, int batchSize) {
        this(store, broker, batchSize, "publisher-" + UUID.randomUUID());
    }

    /** Names the worker in the claim row, so a stuck lease says who held it. */
    public CanonicalEventPublisher(OutboxStore store, Broker broker, int batchSize, String owner) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (batchSize < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        this.batchSize = batchSize;
    }

    /**
     * Publishes one claimed batch and returns how many messages the broker acknowledged.
     *
     * <p>A failed job stays pending with its attempt count increased. The method never throws for a
     * broker problem: the outbox is the record, and the next poll retries.
     */
    public int publishPending() {
        List<PendingJob> batch = store.claim(Destination.CANONICAL_EVENT, batchSize, owner, LEASE);
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
                recordFailure(pending, e);
            } catch (ClaimLostException e) {
                // Another worker owns the job now and will publish it or fail it. Counting this as a
                // publication would be a lie, and retrying it here would fight the owner.
                LOG.warning("a canonical event lease expired before completion; the job has another owner");
            }
        }
        return published;
    }

    private void recordFailure(PendingJob pending, BrokerUnavailableException cause) {
        try {
            store.failed(pending, cause.getMessage());
        } catch (ClaimLostException e) {
            LOG.warning("a canonical event lease expired before the failure could be recorded");
        }
    }
}
