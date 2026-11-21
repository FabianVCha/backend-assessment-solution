package com.meridian.payments.intake.store;

import java.time.Duration;
import java.util.List;

/**
 * The outbox, read and drained per destination.
 *
 * <p>Each destination drains on its own, so a broker that is down cannot delay the Salesforce job and
 * the reverse is also true. A job leaves the pending set only when its destination acknowledged it.
 *
 * <p>A failed attempt is not dropped and does not come back immediately: the store moves the row to
 * its next attempt time with a growing wait, and the due-work query returns only the rows that are due.
 * That schedule is what makes publication at-least-once without turning an outage into a hot loop.
 *
 * <p>Draining goes through {@link #claim}: a worker takes a bounded batch under a lease and gets a token
 * per batch. Two workers polling the same table therefore split the work instead of both sending the
 * same message, and a worker that dies holding a lease does not park the job — the lease expires and the
 * next poll takes it. {@link #pending} is the read-only view for tests and operators: it hands out no
 * token, so a row read that way cannot be completed.
 */
public interface OutboxStore {

    /** Jobs for that destination that are not published and are due now, oldest first, unclaimed. */
    List<PendingJob> pending(Destination destination, int limit);

    /**
     * Takes up to {@code limit} due jobs for {@code owner} until the lease expires.
     *
     * <p>A job whose lease already expired is due again, whoever held it before.
     */
    List<PendingJob> claim(Destination destination, int limit, String owner, Duration lease);

    /** Marks the job published. Requires the claim token it was handed. */
    void published(PendingJob job);

    /** Records a failed attempt and schedules the next one. Requires the claim token. */
    void failed(PendingJob job, String reason);
}
