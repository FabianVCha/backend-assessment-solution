package com.meridian.payments.intake.store;

import com.meridian.payments.intake.canonical.CanonicalEvent;

import java.util.Objects;

/**
 * One unit of work still owed to a destination, with the event it carries.
 *
 * <p>The event travels with the job so the publisher does not have to read the inbox row again and
 * cannot publish something other than what was accepted.
 */
public record PendingJob(OutboxJob job, CanonicalEvent event) {

    public PendingJob {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(event, "event");
        if (!job.provider().equals(event.provider()) || !job.eventId().equals(event.eventId())) {
            throw new IllegalArgumentException("the job does not belong to the event it carries");
        }
    }
}
