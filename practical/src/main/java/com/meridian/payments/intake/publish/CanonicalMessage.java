package com.meridian.payments.intake.publish;

import java.util.Objects;

/**
 * The canonical event as a broker message.
 *
 * <p>{@code messageId} is derived from the event identity, never from a local row id, so a retry
 * carries the same value and broker duplicate detection can collapse it. {@code correlationId} is the
 * canonical correlation id, identical on every retry, so a consumer can tie a discarded duplicate to
 * the event it already processed.
 */
public record CanonicalMessage(String messageId, String correlationId, String body) {

    public CanonicalMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(body, "body");
        if (messageId.isBlank() || messageId.length() > 128) {
            throw new IllegalArgumentException("a broker message id is 1 to 128 characters");
        }
    }
}
