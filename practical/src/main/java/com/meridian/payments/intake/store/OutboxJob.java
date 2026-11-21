package com.meridian.payments.intake.store;

import com.meridian.payments.intake.auth.Provider;

import java.util.Objects;

/**
 * One unit of work owed to one destination for one accepted event.
 *
 * <p>The identity is {@code (provider, eventId, destination)}: the provider belongs in the key because
 * a provider event id is only unique inside its own provider, and the destination belongs in it
 * because one destination failing must not block or duplicate the other.
 */
public record OutboxJob(Provider provider, String eventId, Destination destination) {

    public OutboxJob {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(destination, "destination");
    }
}
