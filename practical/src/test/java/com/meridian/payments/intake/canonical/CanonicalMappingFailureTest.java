package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error cases that matter, not only the happy path.
 *
 * <p>Each failure maps to a code the public error contract already declares, so the HTTP layer can
 * answer 400 without inventing vocabulary. A message never carries the offending value: policy
 * numbers and provider identifiers are sensitive and these messages are meant to be logged.
 */
class CanonicalMappingFailureTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");
    private static final QuetzalCanonicalEventMapper QUETZAL = new QuetzalCanonicalEventMapper();
    private static final AndinaCanonicalEventMapper ANDINA = new AndinaCanonicalEventMapper();

    @Test
    void malformedJsonIsMalformedAndNotASchemaFailure() {
        assertEquals(CanonicalMappingException.Failure.MALFORMED_JSON,
                failureOf(QUETZAL, Provider.QUETZAL, "{\"id\": ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aJsonDocumentThatIsNotAnObjectIsASchemaFailure() {
        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(QUETZAL, Provider.QUETZAL, "[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMissingEventIdentifierIsASchemaFailure() {
        byte[] body = mutated(TestSamples.QZ_SUCCEEDED, "  \"id\": \"evt_01JDQ3K8M2X7\",\n", "");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(QUETZAL, Provider.QUETZAL, body));
    }

    @Test
    void anUnknownChargeTypeIsASchemaFailure() {
        byte[] body = mutated(TestSamples.QZ_SUCCEEDED, "charge.succeeded", "charge.disputed");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(QUETZAL, Provider.QUETZAL, body));
    }

    @Test
    void anUnmappableMerchantReferenceIsItsOwnFailureAndNeverEchoesTheValue() {
        byte[] body = mutated(TestSamples.QZ_SUCCEEDED, "POL-MX-000412785", "REF-12345");

        CanonicalMappingException thrown = assertThrows(CanonicalMappingException.class,
                () -> QUETZAL.map(new WebhookRequest(Provider.QUETZAL, Map.of(), body), RECEIVED_AT));

        assertEquals(CanonicalMappingException.Failure.POLICY_REFERENCE_UNPARSEABLE, thrown.failure());
        assertTrue(!thrown.getMessage().contains("REF-12345"),
                () -> "the message must name the field, not its value: " + thrown.getMessage());
    }

    @Test
    void aCurrencyThatIsNotTheProvidersCurrencyIsRejected() {
        byte[] body = mutated(TestSamples.AN_APROBADA, "\"moneda\": \"COP\"", "\"moneda\": \"EUR\"");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, body));
    }

    @Test
    void anAmountWithThreeDecimalsIsRejectedRatherThanRounded() {
        byte[] body = mutated(TestSamples.AN_APROBADA, "\"valor\": \"185000.00\"", "\"valor\": \"185000.000\"");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, body));
    }

    @Test
    void aZeroAmountIsRejectedBecauseTheContractStartsAtOne() {
        byte[] body = mutated(TestSamples.AN_APROBADA, "\"valor\": \"185000.00\"", "\"valor\": \"0.00\"");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, body));
    }

    @Test
    void anUnknownStateIsASchemaFailure() {
        byte[] body = mutated(TestSamples.AN_APROBADA, "\"estado\": \"APROBADA\"", "\"estado\": \"PROCESANDO\"");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, body));
    }

    @Test
    void theNotificationIdIsRequiredEvenThoughItIsNeverTheEventKey() {
        byte[] body = mutated(TestSamples.AN_APROBADA, "  \"notificacion_id\": \"ntf-8f1c2a77\",\n", "");

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, body));
    }

    @Test
    void aMapperRejectsTheOtherProvidersDocument() {
        byte[] andinaBody = TestSamples.read(TestSamples.AN_APROBADA);
        byte[] quetzalBody = TestSamples.read(TestSamples.QZ_SUCCEEDED);

        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(QUETZAL, Provider.QUETZAL, andinaBody));
        assertEquals(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                failureOf(ANDINA, Provider.ANDINA, quetzalBody));
    }

    private static CanonicalMappingException.Failure failureOf(
            CanonicalEventMapper mapper, Provider provider, byte[] body) {
        CanonicalMappingException thrown = assertThrows(CanonicalMappingException.class,
                () -> mapper.map(new WebhookRequest(provider, Map.of(), body), RECEIVED_AT));
        return thrown.failure();
    }

    private static byte[] mutated(String sample, String from, String to) {
        String body = TestSamples.asText(TestSamples.read(sample));
        String mutated = body.replace(from, to);
        assertNotEquals(body, mutated, () -> "the mutation must change the sample: " + from);
        return mutated.getBytes(StandardCharsets.UTF_8);
    }
}
