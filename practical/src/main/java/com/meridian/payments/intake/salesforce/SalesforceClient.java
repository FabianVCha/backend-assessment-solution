package com.meridian.payments.intake.salesforce;

import java.time.Instant;
import java.util.Objects;

/**
 * One payment fact to write into Salesforce, and the port that writes it.
 *
 * <p>{@code Payment_Key__c} is the business upsert key: the relay never writes by notification id, so two
 * notifications about the same payment update one record instead of creating two.
 *
 * <p>The port returns a classified outcome instead of throwing. The relay has to decide what each failure
 * means — retry, correct, hold, or stop the lane — and an exception with a message string cannot carry
 * that decision. A raw remote message is never the reason code an operator filters on.
 */
public interface SalesforceClient {

    /** The fact to write. No payer data: the payer belonging to a payment is never read by this system. */
    record PaymentWrite(
            String paymentKey,
            String policyNumber,
            String status,
            long amountMinor,
            String currency,
            int currencyExponent,
            Instant statusChangedAt) {

        public PaymentWrite {
            Objects.requireNonNull(paymentKey, "paymentKey");
            Objects.requireNonNull(policyNumber, "policyNumber");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        }
    }

    /**
     * What the destination said, reduced to the classes the relay behaves differently for.
     *
     * <p>{@code LOST_RESPONSE} is the one that matters: the write may have landed, and the only safe action
     * is to stop touching that payment until someone reconciles it.
     */
    enum Result {
        /** Applied. */
        APPLIED,
        /** Some items of a batch applied and others did not. Retried for the missing ones. */
        PARTIAL,
        /** The field or its value is wrong. Retrying cannot fix it. */
        INVALID_FIELD,
        /** The record is locked by another transaction. A 400 that must be retried with backoff. */
        UNABLE_TO_LOCK_ROW,
        /** The session expired. One refresh and one retry are allowed. */
        SESSION_EXPIRED,
        /** The daily API budget is exhausted. The lane stops; retrying makes it worse. */
        RATE_LIMITED,
        /** Remote server error. Bounded retry. */
        SERVER_ERROR,
        /** No response in time. The outcome is unknown. */
        TIMEOUT,
        /** The write landed but the acknowledgement was lost. The outcome is unknown to us. */
        LOST_RESPONSE
    }

    /** Writes the fact and says what happened. Implementations must not throw for a remote failure. */
    Result write(PaymentWrite write);
}
