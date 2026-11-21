package com.meridian.payments.intake.store;

import com.meridian.payments.intake.canonical.CanonicalEvent;

import java.util.Objects;

/**
 * One unit of work still owed to a destination, with the event it carries.
 *
 * <p>The event travels with the job so the publisher does not have to read the inbox row again and
 * cannot publish something other than what was accepted.
 *
 * <p>{@code claimToken} is present when the job was handed to a worker under a lease. Completion
 * presents it, and a token the store no longer recognises means the lease expired and someone else owns
 * the job. A job read for inspection has no token and cannot be completed: reading is not claiming.
 */
public record PendingJob(OutboxJob job, CanonicalEvent event, String claimToken) {

    public PendingJob {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(event, "event");
        if (!job.provider().equals(event.provider()) || !job.eventId().equals(event.eventId())) {
            throw new IllegalArgumentException("the job does not belong to the event it carries");
        }
    }

    /** A job as read, not as claimed. */
    public PendingJob(OutboxJob job, CanonicalEvent event) {
        this(job, event, null);
    }

    public boolean claimed() {
        return claimToken != null;
    }
}
