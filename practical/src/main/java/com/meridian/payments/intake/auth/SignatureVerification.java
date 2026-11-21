package com.meridian.payments.intake.auth;

import java.util.Objects;

/**
 * Outcome of verifying one webhook request.
 *
 * <p>The failure code is safe to log and maps to the public error contract. It never carries the
 * signature, the body, the secret or any payer data.
 */
public record SignatureVerification(boolean valid, Failure failure) {

    public enum Failure {
        NONE,
        SIGNATURE_HEADER_MISSING,
        SIGNATURE_HEADER_MALFORMED,
        SIGNATURE_INVALID,
        TIMESTAMP_OUT_OF_TOLERANCE
    }

    public SignatureVerification {
        Objects.requireNonNull(failure, "failure");
        if (valid != (failure == Failure.NONE)) {
            throw new IllegalArgumentException(
                    "a valid result has no failure and a rejected result must name one");
        }
    }

    public static SignatureVerification accepted() {
        return new SignatureVerification(true, Failure.NONE);
    }

    public static SignatureVerification rejected(Failure failure) {
        if (failure == Failure.NONE) {
            throw new IllegalArgumentException("a rejected result needs a failure code");
        }
        return new SignatureVerification(false, failure);
    }
}
