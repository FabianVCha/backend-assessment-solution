package com.meridian.payments.intake.auth;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link SecretProvider} used by tests and local runs.
 *
 * <p>This is a stub for an external dependency, not a way to ship production secrets: in the
 * design the real implementation reads from the secrets store. The constructor copies every
 * secret so later mutation of the caller's array has no effect.
 */
public final class InMemorySecretProvider implements SecretProvider {

    private final Map<Provider, byte[]> secrets = new EnumMap<>(Provider.class);

    public InMemorySecretProvider(Map<Provider, byte[]> secrets) {
        Objects.requireNonNull(secrets, "secrets").forEach(this::store);
    }

    public static InMemorySecretProvider of(Provider provider, String secret) {
        return new InMemorySecretProvider(Map.of(provider, secret.getBytes(StandardCharsets.UTF_8)));
    }

    private void store(Provider provider, byte[] secret) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(secret, "secret");
        if (secret.length == 0) {
            throw new IllegalArgumentException("empty secret for " + provider);
        }
        secrets.put(provider, secret.clone());
    }

    @Override
    public byte[] secretFor(Provider provider) {
        byte[] secret = secrets.get(Objects.requireNonNull(provider, "provider"));
        if (secret == null) {
            throw new IllegalStateException("no webhook secret configured for " + provider);
        }
        return secret.clone();
    }
}
