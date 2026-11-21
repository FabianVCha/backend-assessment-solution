package com.meridian.payments.intake.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seven published vectors must verify byte for byte, exactly as the providers signed them. */
class OfficialTestVectorsTest {

    @ParameterizedTest(name = "[{index}] Quetzal {0}")
    @MethodSource("quetzalVectors")
    void quetzalPublishedVectorVerifies(Fixtures.QuetzalVector vector) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(Long.parseLong(vector.timestamp())), ZoneOffset.UTC);
        QuetzalSignatureVerifier verifier = new QuetzalSignatureVerifier(Fixtures.SECRETS, clock);
        WebhookRequest request = Fixtures.quetzal(
                Fixtures.quetzalHeader(vector.timestamp(), vector.signature()),
                Fixtures.sample(vector.sample()));

        SignatureVerification result = verifier.verify(request);

        assertTrue(result.valid(), () -> "published Quetzal vector rejected: " + result.failure());
    }

    @ParameterizedTest(name = "[{index}] Andina {0}")
    @MethodSource("andinaVectors")
    void andinaPublishedVectorVerifies(Fixtures.AndinaVector vector) {
        Clock clock = Clock.fixed(OffsetDateTime.parse(vector.timestamp()).toInstant(), ZoneOffset.UTC);
        AndinaSignatureVerifier verifier = new AndinaSignatureVerifier(Fixtures.SECRETS, clock);
        WebhookRequest request = Fixtures.andina(
                vector.timestamp(), vector.signature(), Fixtures.sample(vector.sample()));

        SignatureVerification result = verifier.verify(request);

        assertTrue(result.valid(), () -> "published Andina vector rejected: " + result.failure());
    }

    static Stream<Arguments> quetzalVectors() {
        return Fixtures.QUETZAL_VECTORS.stream().map(vector -> Arguments.of(vector));
    }

    static Stream<Arguments> andinaVectors() {
        return Fixtures.ANDINA_VECTORS.stream().map(vector -> Arguments.of(vector));
    }
}
