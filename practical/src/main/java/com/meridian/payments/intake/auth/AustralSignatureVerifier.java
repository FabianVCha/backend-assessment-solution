package com.meridian.payments.intake.auth;

import com.meridian.payments.intake.auth.SignatureVerification.Failure;

import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Verifies {@code X-Austral-Signature} as Austral Pagos specifies it: lowercase hex HMAC-SHA256 over
 * the raw body, with no timestamp.
 *
 * <p>Because nothing bound to a moment is signed, there is no skew window to enforce and a replay is
 * indistinguishable from a retry at this layer. That is not a gap left open: the provider promises the
 * same {@code eventId} on every attempt, and the inbox deduplicates on {@code (provider, event_id)}, so
 * the second delivery of an event is answered as a duplicate however late it arrives.
 */
public final class AustralSignatureVerifier implements SignatureVerifier {

    static final String SIGNATURE_HEADER = "X-Austral-Signature";

    /** Exactly 64 lowercase hex characters. Uppercase is rejected, as the provider documents it. */
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");
    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.auth");

    private final SecretProvider secrets;

    public AustralSignatureVerifier(SecretProvider secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public SignatureVerification verify(WebhookRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != Provider.AUSTRAL) {
            throw new IllegalArgumentException("not an Austral request: " + request.provider());
        }
        Optional<String> header = request.header(SIGNATURE_HEADER);
        if (header.isEmpty() || header.get().isBlank()) {
            return reject(Failure.SIGNATURE_HEADER_MISSING);
        }
        String signature = header.get();
        if (!SIGNATURE.matcher(signature).matches()) {
            return reject(Failure.SIGNATURE_HEADER_MALFORMED);
        }
        byte[] received = HexFormat.of().parseHex(signature);
        byte[] expected = Hmac.hmacSha256(secrets.secretFor(Provider.AUSTRAL), request.rawBody());
        return Hmac.constantTimeEquals(expected, received)
                ? accept()
                : reject(Failure.SIGNATURE_INVALID);
    }

    private static SignatureVerification accept() {
        LOG.log(Level.FINE, "webhook.auth.accepted provider={0}", Provider.AUSTRAL);
        return SignatureVerification.accepted();
    }

    private static SignatureVerification reject(Failure failure) {
        LOG.log(Level.FINE, "webhook.auth.rejected provider={0} reason={1}",
                new Object[]{Provider.AUSTRAL, failure});
        return SignatureVerification.rejected(failure);
    }
}
