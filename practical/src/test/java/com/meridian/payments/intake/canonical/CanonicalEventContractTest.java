package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every event this component produces must satisfy the contract it publishes.
 *
 * <p>The schema is loaded from {@code docs/api/events/premium-payment.schema.json}, not copied into
 * the test: a hand-maintained copy would keep passing after the published contract changed.
 */
class CanonicalEventContractTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");
    private static final Schema SCHEMA = loadSchema();

    @ParameterizedTest
    @ValueSource(strings = {TestSamples.QZ_SUCCEEDED, TestSamples.QZ_FAILED, TestSamples.QZ_REFUNDED})
    void quetzalSamplesProduceEventsTheContractAccepts(String sample) {
        assertSatisfiesContract(new QuetzalCanonicalEventMapper().map(request(Provider.QUETZAL, sample), RECEIVED_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {TestSamples.AN_PENDIENTE, TestSamples.AN_APROBADA,
            TestSamples.AN_REINTENTO, TestSamples.AN_REVERSADA})
    void andinaSamplesProduceEventsTheContractAccepts(String sample) {
        assertSatisfiesContract(new AndinaCanonicalEventMapper().map(request(Provider.ANDINA, sample), RECEIVED_AT));
    }

    @Test
    void theEventIsClosedAndCarriesThePinnedVersion() {
        CanonicalEvent event = new QuetzalCanonicalEventMapper()
                .map(request(Provider.QUETZAL, TestSamples.QZ_SUCCEEDED), RECEIVED_AT);

        assertEquals(19, event.toJson().size(), "the contract is closed and declares nineteen properties");
        assertEquals("1.0", event.toJson().get("schema_version").asText());
    }

    @Test
    void aRefundReportedAboveTheChargeStillSatisfiesTheContract() {
        String body = TestSamples.asText(TestSamples.read(TestSamples.QZ_REFUNDED))
                .replace("\"amount_refunded\": 125000", "\"amount_refunded\": 200000");
        CanonicalEvent event = new QuetzalCanonicalEventMapper().map(
                new WebhookRequest(Provider.QUETZAL, Map.of(), body.getBytes(StandardCharsets.UTF_8)),
                RECEIVED_AT);

        assertEquals("UNKNOWN", event.refundScope(),
                "a figure above the charge cannot be called full or partial");
        assertSatisfiesContract(event);
    }

    private static void assertSatisfiesContract(CanonicalEvent event) {
        List<Error> violations = SCHEMA.validate(event.toJson());
        assertTrue(violations.isEmpty(), () -> "contract violations: " + violations);
    }

    private static WebhookRequest request(Provider provider, String sample) {
        return new WebhookRequest(provider, Map.of(), TestSamples.read(sample));
    }

    private static Schema loadSchema() {
        Path schema = TestSamples.repoRoot().resolve("docs/api/events/premium-payment.schema.json");
        try (InputStream in = Files.newInputStream(schema)) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load the published event schema from " + schema, e);
        }
    }
}
