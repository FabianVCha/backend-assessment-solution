package com.meridian.payments.intake.salesforce;

import com.meridian.payments.intake.store.ClaimLostException;
import com.meridian.payments.intake.store.PendingJob;
import com.meridian.payments.intake.store.SalesforceStore;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Drains the {@code SALESFORCE} lane: one payment fact at a time, classified, and never twice by accident.
 *
 * <p>Three rules, and each one is visible in the code below:
 *
 * <ul>
 *   <li><b>Nothing older than what the payment already has is written.</b> The projection is read before
 *       the call; an event whose provider timestamp is older is skipped and marked, not sent. A delayed
 *       capture must not overwrite a reversal that already landed.
 *   <li><b>A failed call is classified, not thrown.</b> Invalid field is terminal, a row lock is a retryable
 *       {@code 400}, an expired session is one refresh inside the client and a retry here, a rate limit
 *       stops the lane for the rest of the batch, and a lost response holds the payment instead of
 *       guessing.
 *   <li><b>A held payment is not retried.</b> {@code HELD} means the remote may have the write; retrying
 *       would duplicate a fact and marking it done would claim a success nobody saw. A human or a
 *       reconciliation job resolves it.
 * </ul>
 */
public final class SalesforceRelay {

    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.salesforce");

    /** Long enough for one remote call and the bookkeeping, short enough that a crash costs a tick. */
    public static final Duration LEASE = Duration.ofSeconds(30);

    private final SalesforceStore store;
    private final SalesforceClient client;
    private final int batchSize;
    private final String owner;

    public SalesforceRelay(SalesforceStore store, SalesforceClient client, int batchSize) {
        this(store, client, batchSize, "salesforce-relay-" + UUID.randomUUID());
    }

    public SalesforceRelay(SalesforceStore store, SalesforceClient client, int batchSize, String owner) {
        this.store = Objects.requireNonNull(store, "store");
        this.client = Objects.requireNonNull(client, "client");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (batchSize < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        this.batchSize = batchSize;
    }

    /** Runs one batch and returns how many facts were applied. */
    public int drain() {
        List<PendingJob> batch = store.claimDue(batchSize, owner, LEASE);
        int applied = 0;
        boolean rateLimited = false;
        boolean budgetExhausted = false;
        for (PendingJob job : batch) {
            if (budgetExhausted) {
                deferForBudget(job);
                continue;
            }
            if (rateLimited) {
                // The budget is shared and exhausted: putting the rest back is honest, holding them until
                // the lease expires is not.
                release(job, "RATE_LIMITED");
                continue;
            }
            try {
                if (isOlderThanWhatThePaymentAlreadyHas(job)) {
                    store.skipped(job);
                    continue;
                }
                Optional<LocalDate> reservation = store.tryAcquireBudget();
                if (reservation.isEmpty()) {
                    budgetExhausted = true;
                    deferForBudget(job);
                    continue;
                }
                SalesforceClient.Result result;
                try {
                    result = client.write(writeOf(job));
                } finally {
                    store.releaseBudget(reservation.get());
                }
                switch (result) {
                    case APPLIED -> {
                        store.applied(job);
                        applied++;
                    }
                    case INVALID_FIELD -> store.dead(job, "INVALID_FIELD");
                    case LOST_RESPONSE -> store.held(job, "LOST_RESPONSE");
                    case RATE_LIMITED -> {
                        store.failed(job, "RATE_LIMITED");
                        rateLimited = true;
                    }
                    // Retryable: a locked row is a 400 that must be retried, an expired session is one
                    // refresh inside the client, a partial batch left items behind, and a timeout or a 5xx
                    // is worth another attempt before anyone calls it ambiguous.
                    case PARTIAL -> store.failed(job, "PARTIAL");
                    case UNABLE_TO_LOCK_ROW -> store.failed(job, "UNABLE_TO_LOCK_ROW");
                    case SESSION_EXPIRED -> store.failed(job, "SESSION_EXPIRED");
                    case SERVER_ERROR -> store.failed(job, "SERVER_ERROR");
                    case TIMEOUT -> store.failed(job, "TIMEOUT");
                }
            } catch (ClaimLostException e) {
                LOG.warning("a salesforce lease expired before completion; the job has another owner");
            }
        }
        return applied;
    }

    private boolean isOlderThanWhatThePaymentAlreadyHas(PendingJob job) {
        SalesforceStore.PaymentProjection projection = store.projection(job.event().paymentKey());
        return projection != null
                && job.event().statusChangedAt().isBefore(projection.latestStatusChangedAt());
    }

    private void deferForBudget(PendingJob job) {
        try {
            store.deferForBudget(job);
        } catch (ClaimLostException e) {
            LOG.warning("a salesforce lease expired while the lane was deferred");
        }
    }

    private void release(PendingJob job, String code) {
        try {
            store.failed(job, code);
        } catch (ClaimLostException e) {
            LOG.warning("a salesforce lease expired while the lane was deferred");
        }
    }

    private static SalesforceClient.PaymentWrite writeOf(PendingJob job) {
        return new SalesforceClient.PaymentWrite(
                job.event().paymentKey(),
                job.event().policyNumber(),
                job.event().status(),
                job.event().amountMinor(),
                job.event().currency(),
                job.event().currencyExponent(),
                job.event().statusChangedAt());
    }
}
