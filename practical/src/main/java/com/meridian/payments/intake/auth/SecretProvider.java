package com.meridian.payments.intake.auth;

/**
 * Reads a provider's webhook signing secret from the secrets store.
 *
 * <p>Implementations must never log the secret or include it in an exception message. A secret
 * that is missing is a configuration failure and must fail closed (the caller returns a server
 * error), never be turned into an authentication rejection.
 */
public interface SecretProvider {

    /**
     * @return the secret bytes for the provider; never {@code null} or empty
     * @throws IllegalStateException when no secret is configured for the provider
     */
    byte[] secretFor(Provider provider);
}
