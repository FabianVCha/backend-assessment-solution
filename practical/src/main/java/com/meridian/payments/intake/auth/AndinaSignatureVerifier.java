package com.meridian.payments.intake.auth;

import com.meridian.payments.intake.auth.SignatureVerification.Failure;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Verifies {@code X-Andina-Timestamp} plus {@code X-Andina-Signature} as Andina Pay 2.3
 * specifies: Base64 HMAC-SHA256 over {@code <timestamp header value>.<raw body>}, 600 s
 * tolerance, constant-time comparison. The timestamp text is hashed exactly as received and is
 * never reformatted to UTC first.
 */
public final class AndinaSignatureVerifier implements SignatureVerifier {

    static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    static final String SIGNATURE_HEADER = "X-Andina-Signature";
    static final Duration TOLERANCE = Duration.ofSeconds(600);

    private static final Pattern TIMESTAMP = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "([.][0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9+/]{43}=");
    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.auth");

    private final SecretProvider secrets;
    private final Clock clock;

    public AndinaSignatureVerifier(SecretProvider secrets, Clock clock) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SignatureVerification verify(WebhookRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != Provider.ANDINA) {
            throw new IllegalArgumentException("not an Andina request: " + request.provider());
        }
        Optional<String> timestampHeader = request.header(TIMESTAMP_HEADER);
        Optional<String> signatureHeader = request.header(SIGNATURE_HEADER);
        if (timestampHeader.isEmpty() || timestampHeader.get().isBlank()
                || signatureHeader.isEmpty() || signatureHeader.get().isBlank()) {
            return reject(Failure.SIGNATURE_HEADER_MISSING);
        }
        String timestampText = timestampHeader.get();
        String signatureText = signatureHeader.get();
        if (!TIMESTAMP.matcher(timestampText).matches()) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        Instant timestamp;
        try {
            timestamp = OffsetDateTime.parse(timestampText, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        if (!withinTolerance(timestamp)) {
            return reject(Failure.TIMESTAMP_OUT_OF_TOLERANCE);
        }
        if (!SIGNATURE.matcher(signatureText).matches()) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        byte[] received;
        try {
            received = Base64.getDecoder().decode(signatureText);
        } catch (IllegalArgumentException e) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        if (received.length != 32) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        byte[] expected = Hmac.hmacSha256(
                secrets.secretFor(Provider.ANDINA),
                timestampText.getBytes(StandardCharsets.US_ASCII),
                request.rawBody());
        return Hmac.constantTimeEquals(expected, received)
                ? accept()
                : reject(Failure.SIGNATURE_INVALID);
    }

    private boolean withinTolerance(Instant timestamp) {
        Duration skew = Duration.between(timestamp, clock.instant()).abs();
        return skew.compareTo(TOLERANCE) <= 0;
    }

    private static SignatureVerification accept() {
        LOG.log(Level.FINE, "webhook.auth.accepted provider={0}", Provider.ANDINA);
        return SignatureVerification.accepted();
    }

    private static SignatureVerification reject(Failure failure) {
        LOG.log(Level.FINE, "webhook.auth.rejected provider={0} reason={1}",
                new Object[]{Provider.ANDINA, failure});
        return SignatureVerification.rejected(failure);
    }
}
