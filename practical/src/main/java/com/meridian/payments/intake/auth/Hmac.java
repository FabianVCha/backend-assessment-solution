package com.meridian.payments.intake.auth;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers. Comparison is constant-time. */
final class Hmac {

    private Hmac() {
    }

    /** HMAC-SHA256 over {@code timestamp + "." + body}, as both providers sign it. */
    static byte[] hmacSha256(byte[] key, byte[] timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(timestamp);
            mac.update((byte) '.');
            mac.update(body);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    static boolean constantTimeEquals(byte[] expected, byte[] provided) {
        return MessageDigest.isEqual(expected, provided);
    }
}
