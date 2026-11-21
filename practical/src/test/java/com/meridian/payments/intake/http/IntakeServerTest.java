package com.meridian.payments.intake.http;

import com.meridian.payments.intake.NotificationIntake;
import com.meridian.payments.intake.auth.AndinaSignatureVerifier;
import com.meridian.payments.intake.auth.AustralSignatureVerifier;
import com.meridian.payments.intake.auth.Fixtures;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.QuetzalSignatureVerifier;
import com.meridian.payments.intake.canonical.AndinaCanonicalEventMapper;
import com.meridian.payments.intake.canonical.AustralCanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalEventMapper;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.store.InMemoryIntakeStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport adapter over a real socket.
 *
 * <p>Everything else tests the endpoint as a function. This one proves the last hop: bytes on a port,
 * a signed provider sample, a real HTTP status and a durable event on the other side.
 */
class IntakeServerTest {

    private static final Instant QUETZAL_NOW = Instant.ofEpochSecond(1_795_888_803L);

    @Test
    void aSignedNotificationIsAcceptedOverRealHttp() throws Exception {
        Clock clock = Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC);
        InMemoryIntakeStore store = new InMemoryIntakeStore();
        NotificationIntake intake = new NotificationIntake(
                Map.of(
                        Provider.QUETZAL, new QuetzalSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.ANDINA, new AndinaSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.AUSTRAL, new AustralSignatureVerifier(Fixtures.SECRETS)),
                List.<CanonicalEventMapper>of(
                        new QuetzalCanonicalEventMapper(),
                        new AndinaCanonicalEventMapper(),
                        new AustralCanonicalEventMapper()),
                store,
                clock);

        try (IntakeServer server = IntakeServer.start(new WebhookEndpoint(intake), 0)) {
            Fixtures.QuetzalVector vector = Fixtures.QUETZAL_VECTORS.get(0);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/webhooks/mx/quetzal"))
                    .header("Quetzal-Signature",
                            Fixtures.quetzalHeader(vector.timestamp(), vector.signature()))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                            Fixtures.sample(vector.sample())))
                    .build();

            java.net.http.HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"ACCEPTED\""), response.body());
            assertEquals(1, store.eventCount(), "the event must be durable when the provider gets 2xx");
        }
    }

    @Test
    void aGetOnTheWebhookPathIsNotAWebhook() throws Exception {
        Clock clock = Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC);
        NotificationIntake intake = new NotificationIntake(
                Map.of(
                        Provider.QUETZAL, new QuetzalSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.ANDINA, new AndinaSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.AUSTRAL, new AustralSignatureVerifier(Fixtures.SECRETS)),
                List.<CanonicalEventMapper>of(
                        new QuetzalCanonicalEventMapper(),
                        new AndinaCanonicalEventMapper(),
                        new AustralCanonicalEventMapper()),
                new InMemoryIntakeStore(),
                clock);

        try (IntakeServer server = IntakeServer.start(new WebhookEndpoint(intake), 0)) {
            java.net.http.HttpResponse<String> response = HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + server.port()
                                    + "/webhooks/mx/quetzal"))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
        }
    }
}
