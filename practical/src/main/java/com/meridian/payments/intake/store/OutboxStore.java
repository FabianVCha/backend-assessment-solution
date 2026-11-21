package com.meridian.payments.intake.store;

import java.util.List;

/**
 * The outbox, read by destination.
 *
 * <p>Each destination drains on its own, so a broker that is down cannot delay the Salesforce job and
 * the reverse is also true. A job leaves the pending set only when its destination acknowledged it;
 * a failed attempt stays pending and grows its attempt count, which is what makes publication
 * at-least-once instead of best-effort.
 */
public interface OutboxStore {

    List<PendingJob> pending(Destination destination, int limit);

    void published(PendingJob job);

    void failed(PendingJob job, String reason);
}
