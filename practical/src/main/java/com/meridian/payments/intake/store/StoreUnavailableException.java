package com.meridian.payments.intake.store;

/**
 * The store could not commit. Nothing was written.
 *
 * <p>The caller answers 5xx so the provider retries: durability is the provider's retry until the
 * inbox row and both outbox jobs commit together.
 */
public final class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message) {
        super(message);
    }
}
