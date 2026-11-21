package com.meridian.payments.intake.auth;

import com.meridian.payments.intake.TestSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Austral signs the body and nothing else.
 *
 * <p>The two vectors are the ones the provider publishes, verified over the exact bytes of the sample
 * files: re-serialising the JSON before hashing would change the body and the signature with it, which
 * is the mistake this test exists to catch. Because no timestamp is signed, there is no skew test here;
 * a replay is a duplicate at the inbox, and that is tested where the inbox is.
 */
class AustralSignatureVerifierTest {

    private static final String SECRET = "aus_test_whsec_2b9d6f10";
    private static final SecretProvider SECRETS = new InMemorySecretProvider(Map.of(
            Provider.AUSTRAL, SECRET.getBytes(StandardCharsets.UTF_8)));

    private final AustralSignatureVerifier verifier = new AustralSignatureVerifier(SECRETS);

    @ParameterizedTest
    @CsvSource({
            TestSamples.AU_CONFIRMED + ",ec52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e1482357",
            TestSamples.AU_REVERSED + ",e104a041f3f5fd9bd2bcf2dc746f919fd6f759caeedc450e5f1f247e009d9721"
    })
    void theOfficialVectorsVerifyByteForByte(String sample, String signature) {
        assertTrue(verifier.verify(request(TestSamples.read(sample), signature)).valid());
    }

    @Test
    void oneChangedByteInvalidatesTheSignature() {
        byte[] body = TestSamples.read(TestSamples.AU_CONFIRMED);
        String signature = signatureOf(body);
        byte[] tampered = body.clone();
        tampered[tampered.length - 2] = (byte) '9';

        SignatureVerification result = verifier.verify(request(tampered, signature));

        assertEquals(SignatureVerification.Failure.SIGNATURE_INVALID, result.failure());
    }

    @Test
    void aReserialisedBodyIsADifferentBody() {
        byte[] body = TestSamples.read(TestSamples.AU_CONFIRMED);
        String signature = signatureOf(body);
        byte[] reserialised = body.clone();
        // A parser that adds a trailing newline is enough to break the signature, and it is exactly the
        // kind of "helpful" normalisation a gateway would do.
        byte[] withNewline = java.util.Arrays.copyOf(reserialised, reserialised.length + 1);
        withNewline[withNewline.length - 1] = (byte) '\n';

        assertEquals(SignatureVerification.Failure.SIGNATURE_INVALID,
                verifier.verify(request(withNewline, signature)).failure());
    }

    @Test
    void aMissingHeaderIsRejectedWithItsOwnCode() {
        assertEquals(SignatureVerification.Failure.SIGNATURE_HEADER_MISSING,
                verifier.verify(new WebhookRequest(Provider.AUSTRAL, Map.of(),
                        TestSamples.read(TestSamples.AU_CONFIRMED))).failure());
    }

    @Test
    void aBlankHeaderIsRejectedAsMissing() {
        assertEquals(SignatureVerification.Failure.SIGNATURE_HEADER_MISSING,
                verifier.verify(request(TestSamples.read(TestSamples.AU_CONFIRMED), "   ")).failure());
    }

    @ParameterizedTest
    @CsvSource({
            "ec52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e148235",     // 63 chars
            "EC52AEAC3CBB26DBB1650A46EA82FD44C38F99DD6705E7AB413B2704E1482357",     // uppercase
            "zz52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e1482357",     // not hex
            "ec52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e1482357ff"    // 66 chars
    })
    void aMalformedHeaderIsRejectedWithoutComparingAnything(String signature) {
        assertEquals(SignatureVerification.Failure.SIGNATURE_HEADER_MALFORMED,
                verifier.verify(request(TestSamples.read(TestSamples.AU_CONFIRMED), signature)).failure());
    }

    @Test
    void aSignatureFromAnotherSecretIsInvalid() {
        SecretProvider other = new InMemorySecretProvider(Map.of(
                Provider.AUSTRAL, "aus_test_whsec_00000000".getBytes(StandardCharsets.UTF_8)));

        assertEquals(SignatureVerification.Failure.SIGNATURE_INVALID,
                new AustralSignatureVerifier(other)
                        .verify(request(TestSamples.read(TestSamples.AU_CONFIRMED),
                                signatureOf(TestSamples.read(TestSamples.AU_CONFIRMED))))
                        .failure());
    }

    @Test
    void theSameDeliveryTwiceVerifiesTwiceBecauseTimeIsNotSigned() {
        byte[] body = TestSamples.read(TestSamples.AU_CONFIRMED);
        String signature = signatureOf(body);

        assertTrue(verifier.verify(request(body, signature)).valid());
        assertTrue(verifier.verify(request(body, signature)).valid(),
                "no skew window exists to reject the second delivery; the inbox answers it as a duplicate");
    }

    @Test
    void aRequestFromAnotherProviderIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                new WebhookRequest(Provider.QUETZAL, Map.of(), new byte[0])));
    }

    private static String signatureOf(byte[] body) {
        return java.util.HexFormat.of().formatHex(
                Hmac.hmacSha256(SECRET.getBytes(StandardCharsets.UTF_8), body));
    }

    private static WebhookRequest request(byte[] body, String signature) {
        return new WebhookRequest(Provider.AUSTRAL, Map.of("X-Austral-Signature", signature), body);
    }
}
