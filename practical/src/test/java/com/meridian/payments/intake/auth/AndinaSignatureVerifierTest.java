package com.meridian.payments.intake.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.auth.SignatureVerification.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndinaSignatureVerifierTest {

    private static final String TIMESTAMP = "2026-11-28T14:02:59-05:00";
    private static final String SIGNATURE = "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=";
    private static final byte[] BODY = Fixtures.sample(Fixtures.AN_PENDIENTE);
    private static final Clock CLOCK =
            Clock.fixed(OffsetDateTime.parse(TIMESTAMP).toInstant(), ZoneOffset.UTC);

    @Test
    void rejectsAlteredBodyEvenWithAPublishedHeader() {
        byte[] altered = new String(BODY, StandardCharsets.UTF_8)
                .replace("185000.00", "185000.01")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(BODY, altered), "precondition: the body must change");

        assertEquals(Failure.SIGNATURE_INVALID, verify(TIMESTAMP, SIGNATURE, altered).failure());
    }

    @Test
    void rejectsJsonThatWasParsedAndReEncoded() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        byte[] reencoded = mapper.writeValueAsBytes(mapper.readTree(BODY));
        assertFalse(Arrays.equals(BODY, reencoded), "precondition: re-encoding must change the bytes");

        assertEquals(Failure.SIGNATURE_INVALID, verify(TIMESTAMP, SIGNATURE, reencoded).failure());
    }

    @Test
    void rejectsMissingTimestampHeader() {
        assertEquals(Failure.SIGNATURE_HEADER_MISSING, verify(null, SIGNATURE, BODY).failure());
    }

    @Test
    void rejectsMissingSignatureHeader() {
        assertEquals(Failure.SIGNATURE_HEADER_MISSING, verify(TIMESTAMP, null, BODY).failure());
    }

    @Test
    void rejectsBlankSignatureHeader() {
        assertEquals(Failure.SIGNATURE_HEADER_MISSING, verify(TIMESTAMP, "  ", BODY).failure());
    }

    @ParameterizedTest
    @MethodSource("malformedTimestamps")
    void rejectsMalformedTimestamp(String timestamp) {
        assertEquals(Failure.SIGNATURE_HEADER_MALFORMED, verify(timestamp, SIGNATURE, BODY).failure());
    }

    static Stream<String> malformedTimestamps() {
        return Stream.of(
                "not-a-date",
                "2026-11-28T14:02:59",
                "2026-11-28T14:02:59+0000",
                "2026-11-28 14:02:59-05:00");
    }

    @ParameterizedTest
    @MethodSource("malformedSignatures")
    void rejectsMalformedSignature(String signature) {
        assertEquals(Failure.SIGNATURE_HEADER_MALFORMED,
                verify(TIMESTAMP, signature, BODY).failure());
    }

    static Stream<String> malformedSignatures() {
        return Stream.of(
                "not-base64",
                Base64.getEncoder().encodeToString(new byte[16]),
                SIGNATURE.substring(0, SIGNATURE.length() - 1),
                SIGNATURE.substring(0, SIGNATURE.length() - 2) + "-=");
    }

    @Test
    void rejectsWellFormedSignatureThatDoesNotMatch() {
        String wrong = Base64.getEncoder().encodeToString(new byte[32]);

        assertEquals(Failure.SIGNATURE_INVALID, verify(TIMESTAMP, wrong, BODY).failure());
    }

    @Test
    void hashesTheTimestampHeaderExactlyAsReceived() {
        String sameInstantInUtc = OffsetDateTime.parse(TIMESTAMP)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        assertEquals(Failure.SIGNATURE_INVALID,
                verify(sameInstantInUtc, SIGNATURE, BODY).failure(),
                "the signed string uses the header value, not a normalised instant");
    }

    @ParameterizedTest
    @ValueSource(longs = {600L, -600L})
    void acceptsSkewOfExactlySixHundredSeconds(long skew) {
        Clock shifted = Clock.fixed(CLOCK.instant().plusSeconds(skew), ZoneOffset.UTC);

        assertTrue(verify(shifted, TIMESTAMP, SIGNATURE, BODY).valid());
    }

    @ParameterizedTest
    @ValueSource(longs = {601L, -601L})
    void rejectsSkewBeyondSixHundredSeconds(long skew) {
        Clock shifted = Clock.fixed(CLOCK.instant().plusSeconds(skew), ZoneOffset.UTC);

        assertEquals(Failure.TIMESTAMP_OUT_OF_TOLERANCE,
                verify(shifted, TIMESTAMP, SIGNATURE, BODY).failure());
    }

    @Test
    void treatsHeaderNamesCaseInsensitively() {
        WebhookRequest request = new WebhookRequest(Provider.ANDINA,
                Map.of("x-andina-timestamp", TIMESTAMP, "x-andina-signature", SIGNATURE), BODY);

        assertTrue(new AndinaSignatureVerifier(Fixtures.SECRETS, CLOCK).verify(request).valid());
    }

    @Test
    void missingSecretFailsClosedInsteadOfRejectingTheRequest() {
        AndinaSignatureVerifier verifier =
                new AndinaSignatureVerifier(new InMemorySecretProvider(Map.of()), CLOCK);

        assertThrows(IllegalStateException.class,
                () -> verifier.verify(Fixtures.andina(TIMESTAMP, SIGNATURE, BODY)));
    }

    private static SignatureVerification verify(String timestamp, String signature, byte[] body) {
        return verify(CLOCK, timestamp, signature, body);
    }

    private static SignatureVerification verify(
            Clock clock, String timestamp, String signature, byte[] body) {
        return new AndinaSignatureVerifier(Fixtures.SECRETS, clock)
                .verify(Fixtures.andina(timestamp, signature, body));
    }
}
