package com.meridian.payments.intake;

import com.meridian.payments.intake.auth.SignatureVerification;

import java.util.Objects;

/**
 * The notification was not authentic and was not read.
 *
 * <p>The failure code comes from the signature component and maps to a 401. The message carries the
 * code only: no signature, no body and no payer data belongs in a log line.
 */
public final class WebhookRejectedException extends RuntimeException {

    private final SignatureVerification.Failure failure;

    public WebhookRejectedException(SignatureVerification.Failure failure) {
        super("webhook rejected: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
    }

    public SignatureVerification.Failure failure() {
        return failure;
    }
}
