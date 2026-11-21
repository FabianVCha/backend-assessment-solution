package com.meridian.payments.intake.publish;

/**
 * The broker did not acknowledge the message.
 *
 * <p>The job stays pending. There is no exactly-once claim: if the broker stored the message and the
 * acknowledgement was lost, the next attempt publishes it again, and consumers deduplicate on
 * {@code (provider, event_id)}.
 */
public final class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message) {
        super(message);
    }
}
