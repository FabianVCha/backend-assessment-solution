package com.meridian.payments.intake.store;

import com.meridian.payments.intake.canonical.CanonicalEvent;

/**
 * Records an accepted notification and the work it owes, in one transaction.
 *
 * <p>The contract this interface exists to state:
 *
 * <ul>
 *   <li>{@code (provider, event_id)} is unique. A second delivery of the same event is
 *       {@link Acceptance#DUPLICATE}, adds no outbox job and is still a success for the provider.
 *   <li>An accepted event has exactly one {@link Destination#SALESFORCE} job and exactly one
 *       {@link Destination#CANONICAL_EVENT} job. Retries reuse those rows, they never add more.
 *   <li>Either all three rows commit or none does. A {@link StoreUnavailableException} means nothing
 *       is visible and the 2xx has not been earned yet.
 * </ul>
 *
 * <p>Part B ships an in-memory implementation behind this interface; the design puts PostgreSQL
 * behind it, with the same unique constraint and the same transaction boundary.
 */
public interface IntakeStore {

    Acceptance accept(CanonicalEvent event);
}
