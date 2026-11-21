package com.meridian.payments.intake.store;

/**
 * What a notification did to the store.
 *
 * <p>{@link #DUPLICATE} is a success for the provider: the event was already durable, so the answer is
 * a 2xx with no new work. It is not an error and it must not create a second outbox job.
 */
public enum Acceptance {
    ACCEPTED,
    DUPLICATE
}
