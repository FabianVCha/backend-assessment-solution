package com.meridian.payments.intake.store;

import java.util.List;

/**
 * The outbox, read by destination.
 *
 * <p>Each destination drains on its own, so a broker that is down cannot delay the Salesforce job and
 * the reverse is also true. A job leaves the pending set only when its destination acknowledged it.
 *
 * <p>A failed attempt is not dropped and does not come back immediately: the store moves the row to
 * its next attempt time with a growing wait, and {@link #pending} returns only the rows that are due.
 * That schedule is what makes publication at-least-once without turning an outage into a hot loop.
 */
public interface OutboxStore {

    /** Jobs for that destination that are not published and are due now, oldest first. */
    List<PendingJob> pending(Destination destination, int limit);

    void published(PendingJob job);

    /** Records a failed attempt and schedules the next one. */
    void failed(PendingJob job, String reason);
}
