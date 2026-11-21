package com.meridian.payments.intake.store;

/**
 * The job belongs to someone else now.
 *
 * <p>An attempt that took longer than its lease finds a different token when it tries to finish. That is
 * not an error to retry blindly: the other worker owns the job and will complete it or fail it. The
 * caller logs it and moves on, which is why this exists instead of a silent no-op — a completion that
 * changes no rows and says nothing is how a payment quietly stops moving.
 */
public final class ClaimLostException extends RuntimeException {

    public ClaimLostException(String message) {
        super(message);
    }
}
