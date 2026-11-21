package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.auth.Provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the broker {@code MessageId} from the event identity.
 *
 * <p>The value has to be stable across retries and independent of any local row id, so it is built
 * from {@code (provider, event_id)} only. Service Bus caps the id at 128 characters, so a long
 * identity is hashed instead of truncated: truncation could make two different events collide, and a
 * collision in duplicate detection would silently drop a payment.
 */
public final class MessageIds {

    /** Service Bus caps {@code MessageId} at 128 characters. */
    static final int MAXIMUM_LENGTH = 128;

    private MessageIds() {
    }

    public static String of(Provider provider, String eventId) {
        String candidate = provider.name() + ":" + eventId;
        if (candidate.length() <= MAXIMUM_LENGTH) {
            return candidate;
        }
        return "sha256:" + sha256Hex(provider.name() + "|" + eventId);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for the message id", e);
        }
    }
}
