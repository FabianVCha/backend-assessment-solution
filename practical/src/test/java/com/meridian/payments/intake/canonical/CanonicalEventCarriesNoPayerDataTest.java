package com.meridian.payments.intake.canonical;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * NFR6: payer personal data and the provider's per-attempt notification id never reach the event.
 *
 * <p>The assertion runs on the serialised document, which is what a consumer, a queue or a log line
 * would actually carry. Dropping a field inside the mapper would not prove the value is absent from
 * the wire.
 */
class CanonicalEventCarriesNoPayerDataTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");

    private static final List<String> FORBIDDEN = List.of(
            "@",
            "maria.lopez",
            "jorge.ramirez",
            "Laura",
            "1020304050",
            "tok_qz_",
            "ntf-",
            "\"pagador\"",
            "\"customer\"",
            "documento",
            "correo");

    @ParameterizedTest
    @ValueSource(strings = {TestSamples.QZ_SUCCEEDED, TestSamples.QZ_FAILED, TestSamples.QZ_REFUNDED})
    void quetzalEventsCarryNoPayerData(String sample) {
        CanonicalEvent event = new QuetzalCanonicalEventMapper()
                .map(new WebhookRequest(Provider.QUETZAL, Map.of(), TestSamples.read(sample)), RECEIVED_AT);

        assertClean(event.toJson().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {TestSamples.AN_PENDIENTE, TestSamples.AN_APROBADA,
            TestSamples.AN_REINTENTO, TestSamples.AN_REVERSADA})
    void andinaEventsCarryNoPayerDataAndNoNotificationId(String sample) {
        CanonicalEvent event = new AndinaCanonicalEventMapper()
                .map(new WebhookRequest(Provider.ANDINA, Map.of(), TestSamples.read(sample)), RECEIVED_AT);

        assertClean(event.toJson().toString());
    }

    private static void assertClean(String wireDocument) {
        assertFalse(wireDocument.isBlank(), "the event must serialise to something");
        for (String forbidden : FORBIDDEN) {
            assertFalse(wireDocument.contains(forbidden),
                    () -> "the event leaked '" + forbidden + "': " + wireDocument);
        }
    }
}
