package com.meridian.payments.intake.canonical;

import java.util.Objects;

/**
 * A notification that cannot become a canonical event.
 *
 * <p>The failure code is one of the codes the public error contract declares, so the HTTP layer can
 * answer without inventing a new vocabulary. The message names the offending field and never its
 * value: policy numbers, provider identifiers and payer data are all sensitive, and this message is
 * meant to be loggable.
 */
public final class CanonicalMappingException extends RuntimeException {

    public enum Failure {
        /** The bytes are not a JSON object. */
        MALFORMED_JSON,
        /** Valid JSON that does not satisfy the provider request schema. */
        SCHEMA_VALIDATION_FAILED,
        /** The merchant reference is not a policy number this integration can record. */
        POLICY_REFERENCE_UNPARSEABLE
    }

    private final Failure failure;

    public CanonicalMappingException(Failure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failure;
    }
}
