package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;

import java.time.Instant;

/**
 * Translates one provider notification into the canonical payment event (FR2).
 *
 * <p>Implementations are pure: the same request bytes and the same clock instant always produce the
 * same event, and nothing here reads a datastore, a broker or the network. Authenticity is not
 * checked here; a mapper assumes the bytes were already verified by the provider's signature.
 */
public interface CanonicalEventMapper {

    Provider provider();

    /**
     * @param request    the verified webhook: provider, headers and the exact bytes received
     * @param receivedAt our clock at intake; monitoring only, never used for ordering
     * @throws CanonicalMappingException when the notification cannot become a canonical event
     */
    CanonicalEvent map(WebhookRequest request, Instant receivedAt);
}
