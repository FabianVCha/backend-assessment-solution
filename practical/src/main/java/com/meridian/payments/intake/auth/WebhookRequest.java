package com.meridian.payments.intake.auth;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A webhook exactly as received: provider, headers and raw body bytes.
 *
 * <p>The body stays as bytes on purpose. Both providers sign the bytes they sent, so parsing and
 * re-serialising the JSON before verification would change the digest and reject a valid payment.
 */
public record WebhookRequest(Provider provider, Map<String, String> headers, byte[] rawBody) {

    public WebhookRequest {
        Objects.requireNonNull(provider, "provider");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        rawBody = Objects.requireNonNull(rawBody, "rawBody").clone();
    }

    /** HTTP header names are case-insensitive, so the lookup is too. */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name");
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @Override
    public byte[] rawBody() {
        return rawBody.clone();
    }
}
