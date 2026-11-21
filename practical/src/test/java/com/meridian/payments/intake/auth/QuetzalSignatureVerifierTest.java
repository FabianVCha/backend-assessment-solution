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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuetzalSignatureVerifierTest {

    private static final String TIMESTAMP = "1795888803";
    private static final String SIGNATURE =
            "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031";
    private static final byte[] BODY = Fixtures.sample(Fixtures.QZ_SUCCEEDED);
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), ZoneOffset.UTC);

    @Test
    void acceptsRotationHeaderWhenFirstSignatureMatches() {
        String header = Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE, "0".repeat(64));

        assertTrue(verify(header, BODY).valid());
    }

    @Test
    void acceptsRotationHeaderWhenSecondSignatureMatches() {
        String header = Fixtures.quetzalHeader(TIMESTAMP, "0".repeat(64), SIGNATURE);

        assertTrue(verify(header, BODY).valid());
    }

    @Test
    void rejectsWhenNoRotatedSignatureMatches() {
        String header = Fixtures.quetzalHeader(TIMESTAMP, "0".repeat(64), "1".repeat(64));

        assertEquals(Failure.SIGNATURE_INVALID, verify(header, BODY).failure());
    }

    @Test
    void rejectsAlteredBodyEvenWithAPublishedHeader() {
        byte[] altered = new String(BODY, StandardCharsets.UTF_8)
                .replace("125000", "125001")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(BODY, altered), "precondition: the body must change");

        assertEquals(Failure.SIGNATURE_INVALID,
                verify(Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE), altered).failure());
    }

    @Test
    void rejectsJsonThatWasParsedAndReEncoded() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        byte[] reencoded = mapper.writeValueAsBytes(mapper.readTree(BODY));
        assertFalse(Arrays.equals(BODY, reencoded), "precondition: re-encoding must change the bytes");

        assertEquals(Failure.SIGNATURE_INVALID,
                verify(Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE), reencoded).failure());
    }

    @Test
    void rejectsMissingHeader() {
        assertEquals(Failure.SIGNATURE_HEADER_MISSING, verify(null, BODY).failure());
    }

    @Test
    void rejectsBlankHeader() {
        assertEquals(Failure.SIGNATURE_HEADER_MISSING, verify("   ", BODY).failure());
    }

    @ParameterizedTest
    @MethodSource("malformedHeaders")
    void rejectsMalformedHeader(String header) {
        assertEquals(Failure.SIGNATURE_HEADER_MALFORMED, verify(header, BODY).failure());
    }

    static Stream<String> malformedHeaders() {
        String valid = Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE);
        return Stream.of(
                "t=" + TIMESTAMP,
                "v1=" + SIGNATURE,
                "t=abc,v1=" + SIGNATURE,
                "t=" + TIMESTAMP + ",v1=deadbeef",
                "t=" + TIMESTAMP + ",v1=" + SIGNATURE.toUpperCase(Locale.ROOT),
                valid + ",x=1",
                "T=" + TIMESTAMP + ",v1=" + SIGNATURE,
                valid + ",");
    }

    @ParameterizedTest
    @ValueSource(longs = {300L, -300L})
    void acceptsSkewOfExactlyThreeHundredSeconds(long skew) {
        Clock shifted = Clock.fixed(CLOCK.instant().plusSeconds(skew), ZoneOffset.UTC);

        assertTrue(verify(shifted, Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE), BODY).valid());
    }

    @ParameterizedTest
    @ValueSource(longs = {301L, -301L})
    void rejectsSkewBeyondThreeHundredSeconds(long skew) {
        Clock shifted = Clock.fixed(CLOCK.instant().plusSeconds(skew), ZoneOffset.UTC);

        assertEquals(Failure.TIMESTAMP_OUT_OF_TOLERANCE,
                verify(shifted, Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE), BODY).failure());
    }

    @Test
    void treatsHeaderNamesCaseInsensitively() {
        WebhookRequest request = new WebhookRequest(Provider.QUETZAL,
                Map.of("quetzal-signature", Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE)), BODY);

        assertTrue(new QuetzalSignatureVerifier(Fixtures.SECRETS, CLOCK).verify(request).valid());
    }

    @Test
    void missingSecretFailsClosedInsteadOfRejectingTheRequest() {
        QuetzalSignatureVerifier verifier =
                new QuetzalSignatureVerifier(new InMemorySecretProvider(Map.of()), CLOCK);

        assertThrows(IllegalStateException.class,
                () -> verifier.verify(Fixtures.quetzal(
                        Fixtures.quetzalHeader(TIMESTAMP, SIGNATURE), BODY)));
    }

    private static SignatureVerification verify(String header, byte[] body) {
        return verify(CLOCK, header, body);
    }

    private static SignatureVerification verify(Clock clock, String header, byte[] body) {
        return new QuetzalSignatureVerifier(Fixtures.SECRETS, clock)
                .verify(Fixtures.quetzal(header, body));
    }
}
