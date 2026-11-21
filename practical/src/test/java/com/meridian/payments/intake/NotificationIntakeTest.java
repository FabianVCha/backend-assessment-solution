package com.meridian.payments.intake;

import com.meridian.payments.intake.auth.AndinaSignatureVerifier;
import com.meridian.payments.intake.auth.AustralSignatureVerifier;
import com.meridian.payments.intake.auth.Fixtures;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.QuetzalSignatureVerifier;
import com.meridian.payments.intake.auth.SignatureVerification;
import com.meridian.payments.intake.auth.SignatureVerifier;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.meridian.payments.intake.canonical.AndinaCanonicalEventMapper;
import com.meridian.payments.intake.canonical.AustralCanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalMappingException;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.store.Acceptance;
import com.meridian.payments.intake.store.InMemoryIntakeStore;
import com.meridian.payments.intake.store.StoreUnavailableException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path with the real pieces: signature verification, mapping and store.
 *
 * <p>This is where the two requirements that matter most in Part B become observable. FR4: a retry is
 * answered without a second event or a second job. NFR3: a 2xx is only earned after the event and both
 * jobs are durable, and nothing is stored when the notification is rejected.
 */
class NotificationIntakeTest {

    private static final Instant QUETZAL_NOW = Instant.ofEpochSecond(1_795_888_803L);
    private static final Instant ANDINA_NOW = Instant.parse("2026-11-28T19:05:00Z");

    private final InMemoryIntakeStore store = new InMemoryIntakeStore();

    @Test
    void aSignedQuetzalNotificationIsAcceptedAndDurable() {
        NotificationIntake intake = intake(Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC));

        IntakeOutcome outcome = intake.handle(quetzalRequest(Fixtures.QUETZAL_VECTORS.get(0)));

        assertEquals(Acceptance.ACCEPTED, outcome.acceptance());
        assertEquals("evt_01JDQ3K8M2X7", outcome.event().eventId());
        assertEquals(QUETZAL_NOW, outcome.event().receivedAt());
        assertEquals(1, store.eventCount(), "the event must be durable before the caller answers 2xx");
        assertEquals(2, store.outboxJobs().size(), "both destinations are owed");
    }

    @Test
    void theAndinaRetryIsADuplicateWhileANewStateIsANewEvent() {
        NotificationIntake intake = intake(Clock.fixed(ANDINA_NOW, ZoneOffset.UTC));

        IntakeOutcome accepted = intake.handle(andinaRequest(Fixtures.ANDINA_VECTORS.get(1)));
        IntakeOutcome retry = intake.handle(andinaRequest(Fixtures.ANDINA_VECTORS.get(2)));

        assertEquals(Acceptance.ACCEPTED, accepted.acceptance());
        assertEquals(Acceptance.DUPLICATE, retry.acceptance(), "the same state delivered twice");
        assertTrue(retry.duplicate());
        assertEquals(accepted.event().eventId(), retry.event().eventId());
        assertEquals(1, store.eventCount(), "the retry must not add an event");
        assertEquals(2, store.outboxJobs().size(), "the retry must not owe new work");

        IntakeOutcome newState = intake.handle(andinaRequest(Fixtures.ANDINA_VECTORS.get(0)));

        assertEquals(Acceptance.ACCEPTED, newState.acceptance(), "a different state is a new event");
        assertNotEquals(accepted.event().eventId(), newState.event().eventId());
        assertEquals(2, store.eventCount());
        assertEquals(4, store.outboxJobs().size());
    }

    @Test
    void anInvalidSignatureIsRejectedBeforeAnythingIsStored() {
        byte[] tampered = new String(Fixtures.sample(Fixtures.QZ_SUCCEEDED), StandardCharsets.UTF_8)
                .replace("\"amount\": 125000", "\"amount\": 125001")
                .getBytes(StandardCharsets.UTF_8);
        WebhookRequest request = Fixtures.quetzal(
                Fixtures.quetzalHeader(Fixtures.QUETZAL_VECTORS.get(0).timestamp(),
                        Fixtures.QUETZAL_VECTORS.get(0).signature()),
                tampered);

        WebhookRejectedException thrown = assertThrows(WebhookRejectedException.class,
                () -> intake(Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC)).handle(request));

        assertEquals(SignatureVerification.Failure.SIGNATURE_INVALID, thrown.failure());
        assertEquals(0, store.eventCount());
        assertEquals(List.of(), store.outboxJobs());
    }

    @Test
    void anUnmappableNotificationIsRejectedAndStoredNowhere() {
        NotificationIntake intake = new NotificationIntake(
                Map.of(Provider.QUETZAL, alwaysAuthentic(),
                        Provider.ANDINA, alwaysAuthentic(),
                        Provider.AUSTRAL, alwaysAuthentic()),
                mappers(),
                store,
                Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC));
        String body = new String(Fixtures.sample(Fixtures.QZ_SUCCEEDED), StandardCharsets.UTF_8)
                .replace("POL-MX-000412785", "REF-12345");

        CanonicalMappingException thrown = assertThrows(CanonicalMappingException.class,
                () -> intake.handle(Fixtures.quetzal("t=1795888803,v1=" + "0".repeat(64),
                        body.getBytes(StandardCharsets.UTF_8))));

        assertEquals(CanonicalMappingException.Failure.POLICY_REFERENCE_UNPARSEABLE, thrown.failure());
        assertEquals(0, store.eventCount());
    }

    @Test
    void authenticityIsCheckedBeforeThePayloadIsParsed() {
        NotificationIntake intake = new NotificationIntake(
                Map.of(Provider.QUETZAL, alwaysRejected(),
                        Provider.ANDINA, alwaysRejected(),
                        Provider.AUSTRAL, alwaysRejected()),
                mappers(),
                store,
                Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC));

        assertThrows(WebhookRejectedException.class,
                () -> intake.handle(Fixtures.quetzal(null, "{not json".getBytes(StandardCharsets.UTF_8))));

        assertEquals(0, store.eventCount());
    }

    @Test
    void aStoreFailureIsNotHiddenFromTheCallerAndTheRetryEarnsDurability() {
        NotificationIntake intake = intake(Clock.fixed(QUETZAL_NOW, ZoneOffset.UTC));
        store.failNextAcceptMidTransaction();

        assertThrows(StoreUnavailableException.class,
                () -> intake.handle(quetzalRequest(Fixtures.QUETZAL_VECTORS.get(0))));

        assertEquals(0, store.eventCount());
        assertEquals(List.of(), store.outboxJobs());

        IntakeOutcome retry = intake.handle(quetzalRequest(Fixtures.QUETZAL_VECTORS.get(0)));

        assertEquals(Acceptance.ACCEPTED, retry.acceptance(), "the provider's retry is what earns durability");
        assertEquals(1, store.eventCount());
        assertEquals(2, store.outboxJobs().size());
    }

    private NotificationIntake intake(Clock clock) {
        return new NotificationIntake(
                Map.of(
                        Provider.QUETZAL, new QuetzalSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.ANDINA, new AndinaSignatureVerifier(Fixtures.SECRETS, clock),
                        Provider.AUSTRAL, new AustralSignatureVerifier(Fixtures.SECRETS)),
                mappers(),
                store,
                clock);
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

    private static SignatureVerifier alwaysRejected() {
        return request -> SignatureVerification.rejected(
                SignatureVerification.Failure.SIGNATURE_HEADER_MISSING);
    }

    private static WebhookRequest quetzalRequest(Fixtures.QuetzalVector vector) {
        return Fixtures.quetzal(
                Fixtures.quetzalHeader(vector.timestamp(), vector.signature()),
                Fixtures.sample(vector.sample()));
    }

    private static WebhookRequest andinaRequest(Fixtures.AndinaVector vector) {
        return Fixtures.andina(vector.timestamp(), vector.signature(), Fixtures.sample(vector.sample()));
    }
}
