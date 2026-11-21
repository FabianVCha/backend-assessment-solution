package com.meridian.payments.intake.publish;

/**
 * Publishes one canonical message to the topic.
 *
 * <p>Part B ships a recording stub; the design puts Azure Service Bus behind this interface. The
 * method has to be all-or-nothing from the caller's point of view: return normally when the broker
 * acknowledged the message, throw {@link BrokerUnavailableException} otherwise.
 */
public interface Broker {

    void publish(CanonicalMessage message);
}
