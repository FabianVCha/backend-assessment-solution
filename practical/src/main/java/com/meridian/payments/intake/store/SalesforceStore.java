package com.meridian.payments.intake.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The Salesforce lane's own view of the outbox: claim a payment fact, decide, record what happened.
 *
 * <p>It is separate from {@link OutboxStore} because the decisions are different. The canonical lane only
 * has sent or not sent; this lane has applied, terminally rejected, and the ambiguous middle where the
 * remote system may or may not have the write. That middle is what {@code HELD} exists for.
 */
public interface SalesforceStore {

    /** What the projection knows about a payment, for the ordering decision. */
    record PaymentProjection(String latestStatus, Instant latestStatusChangedAt, String remoteAppliedStatus) {
    }

    /** Claims due Salesforce jobs, skipping any payment that already has a write in flight. */
    java.util.List<PendingJob> claimDue(int limit, String owner, java.time.Duration lease);

    /** The payment's projection, or {@code null} when the payment has no row. */
    PaymentProjection projection(String paymentKey);

    /** The write landed. Marks the job done and records what Salesforce now holds. */
    void applied(PendingJob job);

    /** The event was older than the payment's newest fact: nothing was written and nothing is owed. */
    void skipped(PendingJob job);

    /** Terminal: retrying cannot fix it. A human has to. */
    void dead(PendingJob job, String code);

    /** Ambiguous: the remote may hold the write. Held until someone reconciles it. */
    void held(PendingJob job, String code);

    /** Retryable: back to pending with the next attempt scheduled. */
    void failed(PendingJob job, String code);

    /** Reserves a call and one in-flight slot, returning the UTC bucket to release after the call. */
    Optional<LocalDate> tryAcquireBudget();

    /** Releases concurrency capacity only; the attempted call still counts in the rolling window. */
    void releaseBudget(LocalDate day);

    /** Defers a claimed job without counting an attempt: no write was made. */
    void deferForBudget(PendingJob job);
}
