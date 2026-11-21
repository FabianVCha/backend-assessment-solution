package com.meridian.payments.intake.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.NotificationIntake;
import com.meridian.payments.intake.auth.AndinaSignatureVerifier;
import com.meridian.payments.intake.auth.AustralSignatureVerifier;
import com.meridian.payments.intake.auth.Fixtures;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.QuetzalSignatureVerifier;
import com.meridian.payments.intake.auth.SignatureVerification;
import com.meridian.payments.intake.auth.SignatureVerifier;
import com.meridian.payments.intake.canonical.AndinaCanonicalEventMapper;
import com.meridian.payments.intake.canonical.AustralCanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalEventMapper;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.store.InMemoryIntakeStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP surface against the published contract.
 *
 * <p>Every status asserted here is a status {@code docs/api/webhooks.openapi.yaml} declares for that
 * path, in the order the contract documents the checks.
 */
class WebhookEndpointTest {

    private static final String QUETZAL_PATH = "/webhooks/mx/quetzal";
    private static final String ANDINA_PATH = "/webhooks/co/andina";
    private static final Instant QUETZAL_NOW = Instant.ofEpochSecond(1_795_888_803L);
    private static final Instant ANDINA_NOW = Instant.parse("2026-11-28T19:05:00Z");
    /** Austral signs nothing time-bound, so any instant serves; this one is the sample's send time. */
    private static final Instant AUSTRAL_NOW = Instant.parse("2026-11-28T17:05:45Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryIntakeStore store = new InMemoryIntakeStore();

    @Test
    void anAcceptedQuetzalNotificationAnswers200WithAReceipt() {
        HttpResponse response = endpoint(QUETZAL_NOW).handle(
                new HttpRequest(QUETZAL_PATH, quetzalHeaders(0), Fixtures.sample(Fixtures.QZ_SUCCEEDED)));

        assertEquals(200, response.status());
        assertEquals("ACCEPTED", body(response).get("status").asText());
        assertEquals(1, store.eventCount());
    }

    @Test
    void aDuplicateQuetzalRetryAnswers200WithTheDuplicateStatus() {
        WebhookEndpoint endpoint = endpoint(QUETZAL_NOW);
        byte[] payload = Fixtures.sample(Fixtures.QZ_SUCCEEDED);
        endpoint.handle(new HttpRequest(QUETZAL_PATH, quetzalHeaders(0), payload));

        HttpResponse duplicate = endpoint.handle(new HttpRequest(QUETZAL_PATH, quetzalHeaders(0), payload));

        assertEquals(200, duplicate.status());
        assertEquals("DUPLICATE", body(duplicate).get("status").asText());
        assertEquals(1, store.eventCount());
    }

    @Test
    void aDuplicateAndinaRetryAnswers204WithNoBody() {
        WebhookEndpoint endpoint = endpoint(ANDINA_NOW);
        HttpResponse first = endpoint.handle(
                new HttpRequest(ANDINA_PATH, andinaHeaders(1), Fixtures.sample(Fixtures.AN_APROBADA)));

        HttpResponse retry = endpoint.handle(
                new HttpRequest(ANDINA_PATH, andinaHeaders(2), Fixtures.sample(Fixtures.AN_REINTENTO)));

        assertEquals(200, first.status());
        assertEquals("ACCEPTED", body(first).get("status").asText());
        assertEquals(204, retry.status());
        assertEquals("", retry.body());
    }

    @Test
    void anOversizedBodyIsA413BeforeTheSignatureIsLookedAt() {
        byte[] tooLarge = new byte[WebhookEndpoint.MAX_BODY_BYTES + 1];

        HttpResponse response = endpoint(QUETZAL_NOW)
                .handle(new HttpRequest(QUETZAL_PATH, Map.of(), tooLarge));

        assertEquals(413, response.status());
        assertEquals("PAYLOAD_TOO_LARGE", errorCode(response));
        assertEquals(0, store.eventCount());
    }

    @Test
    void anInvalidSignatureIsA401WithTheContractCode() {
        byte[] tampered = new String(Fixtures.sample(Fixtures.QZ_SUCCEEDED), StandardCharsets.UTF_8)
                .replace("\"amount\": 125000", "\"amount\": 125001")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse response = endpoint(QUETZAL_NOW)
                .handle(new HttpRequest(QUETZAL_PATH, quetzalHeaders(0), tampered));

        assertEquals(401, response.status());
        assertEquals("SIGNATURE_INVALID", errorCode(response));
        assertFalse(body(response).get("error").get("retryable").asBoolean());
        assertEquals(0, store.eventCount());
    }

    @Test
    void anUnmappableReferenceIsA400WithItsOwnCodeAndNoEcho() {
        String payload = new String(Fixtures.sample(Fixtures.QZ_SUCCEEDED), StandardCharsets.UTF_8)
                .replace("POL-MX-000412785", "REF-12345");

        HttpResponse response = endpoint(QUETZAL_NOW, alwaysAuthentic(), alwaysAuthentic())
                .handle(new HttpRequest(QUETZAL_PATH, Map.of("Quetzal-Signature", "irrelevant"),
                        payload.getBytes(StandardCharsets.UTF_8)));

        assertEquals(400, response.status());
        assertEquals("POLICY_REFERENCE_UNPARSEABLE", errorCode(response));
        assertFalse(response.body().contains("REF-12345"), () -> "the response echoed the request: " + response.body());
        assertEquals(0, store.eventCount());
    }

    @Test
    void aStoreFailureIsA503WithRetryAfter() {
        store.failNextAcceptMidTransaction();

        HttpResponse response = endpoint(QUETZAL_NOW)
                .handle(new HttpRequest(QUETZAL_PATH, quetzalHeaders(0), Fixtures.sample(Fixtures.QZ_SUCCEEDED)));

        assertEquals(503, response.status());
        assertEquals("STORE_UNAVAILABLE", errorCode(response));
        assertTrue(body(response).get("error").get("retryable").asBoolean());
        assertEquals("5", response.headers().get("Retry-After"));
        assertEquals(0, store.eventCount());
    }

    @Test
    void aPathThatIsNotPublishedIsA404() {
        HttpResponse response = endpoint(QUETZAL_NOW)
                .handle(new HttpRequest("/webhooks/br/austral", Map.of(), new byte[0]));

        assertEquals(404, response.status());
    }

    @Test
    void anAustralNotificationIsAcceptedAndAnsweredWithAReceipt() {
        HttpRequest request = new HttpRequest("/webhooks/cl/austral",
                Map.of("X-Austral-Signature", Fixtures.AUSTRAL_VECTORS.get(0).signature()),
                Fixtures.sample(Fixtures.AU_CONFIRMED));

        HttpResponse response = endpoint(AUSTRAL_NOW).handle(request);

        assertEquals(200, response.status());
        assertEquals("ACCEPTED", body(response).get("status").asText());
        assertEquals(1, store.eventCount());
        assertEquals(2, store.outboxJobs().size());
    }

    @Test
    void anAustralDuplicateIsAReceiptAndNotTheAndina204() {
        HttpRequest request = new HttpRequest("/webhooks/cl/austral",
                Map.of("X-Austral-Signature", Fixtures.AUSTRAL_VECTORS.get(0).signature()),
                Fixtures.sample(Fixtures.AU_CONFIRMED));
        WebhookEndpoint endpoint = endpoint(AUSTRAL_NOW);

        endpoint.handle(request);
        HttpResponse duplicate = endpoint.handle(request);

        assertEquals(200, duplicate.status(),
                "this provider expects any 2xx, and the receipt is the same shape as the first one");
        assertEquals("DUPLICATE", body(duplicate).get("status").asText());
    }

    @Test
    void anAustralSignatureThatDoesNotMatchIsA401() {
        HttpRequest request = new HttpRequest("/webhooks/cl/austral",
                Map.of("X-Austral-Signature", "0".repeat(64)),
                Fixtures.sample(Fixtures.AU_CONFIRMED));

        HttpResponse response = endpoint(AUSTRAL_NOW).handle(request);

        assertEquals(401, response.status());
        assertEquals("SIGNATURE_INVALID", errorCode(response));
        assertEquals(0, store.eventCount());
    }

    private WebhookEndpoint endpoint(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return endpoint(now,
                new QuetzalSignatureVerifier(Fixtures.SECRETS, clock),
                new AndinaSignatureVerifier(Fixtures.SECRETS, clock),
                new AustralSignatureVerifier(Fixtures.SECRETS));
    }

    private WebhookEndpoint endpoint(
            Instant now, SignatureVerifier quetzal, SignatureVerifier andina) {
        return endpoint(now, quetzal, andina, new AustralSignatureVerifier(Fixtures.SECRETS));
    }

    private WebhookEndpoint endpoint(
            Instant now, SignatureVerifier quetzal, SignatureVerifier andina, SignatureVerifier austral) {
        return new WebhookEndpoint(new NotificationIntake(
                Map.of(Provider.QUETZAL, quetzal,
                        Provider.ANDINA, andina,
                        Provider.AUSTRAL, austral),
                mappers(),
                store,
                Clock.fixed(now, ZoneOffset.UTC)));
    }

    private static List<CanonicalEventMapper> mappers() {
        return List.of(
                new QuetzalCanonicalEventMapper(),
                new AndinaCanonicalEventMapper(),
                new AustralCanonicalEventMapper());
    }

    private static SignatureVerifier alwaysAuthentic() {
        return request -> SignatureVerification.accepted();
    }

    private static Map<String, String> quetzalHeaders(int vector) {
        Fixtures.QuetzalVector v = Fixtures.QUETZAL_VECTORS.get(vector);
        return Map.of("Quetzal-Signature", Fixtures.quetzalHeader(v.timestamp(), v.signature()));
    }

    private static Map<String, String> andinaHeaders(int vector) {
        Fixtures.AndinaVector v = Fixtures.ANDINA_VECTORS.get(vector);
        return Map.of("X-Andina-Timestamp", v.timestamp(), "X-Andina-Signature", v.signature());
    }

    private static JsonNode body(HttpResponse response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("the endpoint answered something that is not JSON", e);
        }
    }

    private static String errorCode(HttpResponse response) {
        return body(response).get("error").get("code").asText();
    }
}
