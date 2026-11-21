package com.meridian.payments.intake;

import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.store.Acceptance;

import java.util.Objects;

/**
 * What the intake did with one webhook.
 *
 * <p>Both statuses are a success for the provider: {@link Acceptance#ACCEPTED} means the event and
 * both outbox jobs are durable, {@link Acceptance#DUPLICATE} means they already were. Neither says
 * anything about Salesforce or about a consumer having read the event.
 */
public record IntakeOutcome(Acceptance acceptance, CanonicalEvent event) {

    public IntakeOutcome {
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(event, "event");
    }

    public boolean duplicate() {
        return acceptance == Acceptance.DUPLICATE;
    }
}
