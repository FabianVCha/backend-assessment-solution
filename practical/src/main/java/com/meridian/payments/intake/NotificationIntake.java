package com.meridian.payments.intake;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.SignatureVerification;
import com.meridian.payments.intake.auth.SignatureVerifier;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.canonical.CanonicalEventMapper;
import com.meridian.payments.intake.store.Acceptance;
import com.meridian.payments.intake.store.IntakeStore;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One notification, in the order the contract promises: authenticity, then the event, then durability.
 *
 * <p>Verification comes first on purpose. A notification whose signature does not match is never
 * parsed, so a hostile body cannot reach the JSON reader and an authentic body cannot be rejected for
 * a reason that belongs to the parser.
 *
 * <p>Nothing here knows about HTTP. The transport translates a {@link WebhookRejectedException} to
 * 401, a {@link com.meridian.payments.intake.canonical.CanonicalMappingException} to 400 and a
 * {@link com.meridian.payments.intake.store.StoreUnavailableException} to 503; a 2xx is only earned
 * once the store returned {@link Acceptance#ACCEPTED} or {@link Acceptance#DUPLICATE}.
 */
public final class NotificationIntake {

    private final Map<Provider, SignatureVerifier> verifiers;
    private final Map<Provider, CanonicalEventMapper> mappers;
    private final IntakeStore store;
    private final Clock clock;

    public NotificationIntake(
            Map<Provider, SignatureVerifier> verifiers,
            List<CanonicalEventMapper> mappers,
            IntakeStore store,
            Clock clock) {
        this.verifiers = Map.copyOf(Objects.requireNonNull(verifiers, "verifiers"));
        this.mappers = Objects.requireNonNull(mappers, "mappers").stream()
                .collect(Collectors.toUnmodifiableMap(CanonicalEventMapper::provider, Function.identity()));
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (Provider provider : Provider.values()) {
            if (!this.verifiers.containsKey(provider) || !this.mappers.containsKey(provider)) {
                throw new IllegalArgumentException("no verifier or mapper wired for " + provider);
            }
        }
    }

    public IntakeOutcome handle(WebhookRequest request) {
        Objects.requireNonNull(request, "request");
        Provider provider = request.provider();

        SignatureVerification verification = verifiers.get(provider).verify(request);
        if (!verification.valid()) {
            throw new WebhookRejectedException(verification.failure());
        }

        CanonicalEvent event = mappers.get(provider).map(request, clock.instant());
        return new IntakeOutcome(store.accept(event), event);
    }
}
