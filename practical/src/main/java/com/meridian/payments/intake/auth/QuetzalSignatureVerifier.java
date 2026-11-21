package com.meridian.payments.intake.auth;

import com.meridian.payments.intake.auth.SignatureVerification.Failure;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Verifies {@code Quetzal-Signature: t=<unix>,v1=<hex>[,v1=<hex>]} as Quetzal Pagos
 * 2025-06-01 specifies: HMAC-SHA256 over {@code <t>.<raw body>}, 300 s tolerance, any
 * {@code v1} may match during secret rotation, constant-time comparison.
 */
public final class QuetzalSignatureVerifier implements SignatureVerifier {

    static final String SIGNATURE_HEADER = "Quetzal-Signature";
    static final Duration TOLERANCE = Duration.ofSeconds(300);

    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{10}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();
    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.auth");

    private final SecretProvider secrets;
    private final Clock clock;

    public QuetzalSignatureVerifier(SecretProvider secrets, Clock clock) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SignatureVerification verify(WebhookRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != Provider.QUETZAL) {
            throw new IllegalArgumentException("not a Quetzal request: " + request.provider());
        }
        Optional<String> header = request.header(SIGNATURE_HEADER);
        if (header.isEmpty() || header.get().isBlank()) {
            return reject(Failure.SIGNATURE_HEADER_MISSING);
        }
        ParsedHeader parsed = parse(header.get());
        if (parsed == null) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        if (!withinTolerance(parsed.timestamp())) {
            return reject(Failure.TIMESTAMP_OUT_OF_TOLERANCE);
        }
        byte[] expected = Hmac.hmacSha256(
                secrets.secretFor(Provider.QUETZAL),
                parsed.timestampText().getBytes(StandardCharsets.US_ASCII),
                request.rawBody());
        for (byte[] candidate : parsed.signatures()) {
            if (Hmac.constantTimeEquals(expected, candidate)) {
                return accept();
            }
        }
        return reject(Failure.SIGNATURE_INVALID);
    }

    private boolean withinTolerance(Instant timestamp) {
        Duration skew = Duration.between(timestamp, clock.instant()).abs();
        return skew.compareTo(TOLERANCE) <= 0;
    }

    /**
     * Parses {@code t=<10 digits>,v1=<64 lowercase hex>[,...]}. The timestamp text is kept as
     * received because it is part of the signed string. Returns {@code null} when malformed.
     */
    private static ParsedHeader parse(String header) {
        String[] parts = header.split(",", -1);
        if (parts.length < 2 || !parts[0].startsWith("t=")) {
            return null;
        }
        String timestampText = parts[0].substring(2);
        if (!TIMESTAMP.matcher(timestampText).matches()) {
            return null;
        }
        List<byte[]> signatures = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.startsWith("v1=")) {
                return null;
            }
            String signature = part.substring(3);
            if (!SIGNATURE.matcher(signature).matches()) {
                return null;
            }
            signatures.add(HEX.parseHex(signature));
        }
        return new ParsedHeader(
                Instant.ofEpochSecond(Long.parseLong(timestampText)), timestampText, signatures);
    }

    private static SignatureVerification accept() {
        LOG.log(Level.FINE, "webhook.auth.accepted provider={0}", Provider.QUETZAL);
        return SignatureVerification.accepted();
    }

    private static SignatureVerification reject(Failure failure) {
        LOG.log(Level.FINE, "webhook.auth.rejected provider={0} reason={1}",
                new Object[]{Provider.QUETZAL, failure});
        return SignatureVerification.rejected(failure);
    }

    private record ParsedHeader(Instant timestamp, String timestampText, List<byte[]> signatures) {
    }
}
