package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.store.Acceptance;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.InMemoryIntakeStore;
import com.meridian.payments.intake.store.OutboxJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publication to the canonical topic (FR6).
 *
 * <p>The properties under test are the ones a consumer depends on: the message id is derived from the
 * event identity and stable across retries, a published job is not published again, a job whose
 * acknowledgement was lost is published again (at-least-once, never lost), and the Salesforce
 * destination is untouched by this publisher.
 */
class CanonicalEventPublisherTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");

    private final InMemoryIntakeStore store = new InMemoryIntakeStore();
    private final RecordingBroker broker = new RecordingBroker();
    private final CanonicalEventPublisher publisher = new CanonicalEventPublisher(store, broker, 50);

    @Test
    void thePendingCanonicalEventIsPublishedWithADerivedMessageId() {
        CanonicalEvent event = acceptQuetzalSample();

        assertEquals(1, publisher.publishPending());

        CanonicalMessage message = broker.published().get(0);
        assertEquals("QUETZAL:" + event.eventId(), message.messageId());
        assertEquals(event.correlationId(), message.correlationId());
        assertTrue(message.body().contains("\"event_id\":\"" + event.eventId() + "\""),
                () -> "the body is the canonical event: " + message.body());
        assertEquals(0, store.pending(Destination.CANONICAL_EVENT, 10).size());
    }

    @Test
    void aPublishedEventIsNotPublishedTwice() {
        acceptQuetzalSample();

        publisher.publishPending();
        assertEquals(0, publisher.publishPending());

        assertEquals(1, broker.published().size());
    }

    @Test
    void aLongIdentityBecomesAStableHashInsteadOfATruncatedId() {
        String longId = "evt_" + "x".repeat(130);
        accept(event(longId));

        publisher.publishPending();

        String messageId = broker.published().get(0).messageId();
        assertTrue(messageId.startsWith("sha256:"), messageId);
        assertEquals(71, messageId.length(), "sha256: plus 64 hex characters");
        assertEquals(MessageIds.of(Provider.QUETZAL, longId), messageId, "the value is deterministic");
    }

    @Test
    void aBrokerFailureLeavesTheJobPendingAndTheRetryPublishesIt() {
        CanonicalEvent event = acceptQuetzalSample();
        broker.failNextPublish();

        assertEquals(0, publisher.publishPending());

        assertEquals(1, store.pending(Destination.CANONICAL_EVENT, 10).size());
        assertEquals(1, store.attempts(new OutboxJob(Provider.QUETZAL, event.eventId(), Destination.CANONICAL_EVENT)));

        assertEquals(1, publisher.publishPending());
        assertEquals(1, broker.published().size());
        assertEquals(0, store.pending(Destination.CANONICAL_EVENT, 10).size());
    }

    @Test
    void aLostAcknowledgementPublishesTheSameMessageAgain() {
        CanonicalEvent event = acceptQuetzalSample();
        broker.loseNextAcknowledgement();

        assertEquals(0, publisher.publishPending(), "the ack was lost, so the job is not done");
        assertEquals(1, broker.published().size(), "the broker did store it");

        assertEquals(1, publisher.publishPending());

        assertEquals(2, broker.published().size(), "at-least-once: the consumer may see it twice");
        assertEquals("QUETZAL:" + event.eventId(), broker.published().get(0).messageId());
        assertEquals(broker.published().get(0).messageId(), broker.published().get(1).messageId(),
                "the same id is what lets the consumer collapse the duplicate");
    }

    @Test
    void theSalesforceDestinationIsNotTouchedByThePublisher() {
        CanonicalEvent event = acceptQuetzalSample();

        publisher.publishPending();

        assertEquals(0, store.pending(Destination.CANONICAL_EVENT, 10).size());
        assertEquals(1, store.pending(Destination.SALESFORCE, 10).size(),
                "the relay owns that destination, not the publisher");
        assertEquals(0, store.attempts(new OutboxJob(Provider.QUETZAL, event.eventId(), Destination.SALESFORCE)),
                "the publisher never attempts that destination");
    }

    @Test
    void theBatchSizeIsRespected() {
        accept(event("evt_a"));
        accept(event("evt_b"));
        accept(event("evt_c"));
        CanonicalEventPublisher small = new CanonicalEventPublisher(store, broker, 2);

        assertEquals(2, small.publishPending());

        assertEquals(2, broker.published().size());
        assertEquals(1, store.pending(Destination.CANONICAL_EVENT, 10).size());
        assertEquals(1, small.publishPending());
    }

    private CanonicalEvent acceptQuetzalSample() {
        CanonicalEvent event = new QuetzalCanonicalEventMapper().map(
                new WebhookRequest(Provider.QUETZAL, Map.of(), TestSamples.read(TestSamples.QZ_SUCCEEDED)),
                RECEIVED_AT);
        assertEquals(Acceptance.ACCEPTED, store.accept(event));
        return event;
    }

    private void accept(CanonicalEvent event) {
        assertEquals(Acceptance.ACCEPTED, store.accept(event));
    }

    private static CanonicalEvent event(String eventId) {
        return new CanonicalEvent(
                eventId,
                Provider.QUETZAL,
                "MX",
                "QZ-MX-ch",
                "ch",
                "POL-MX-000412785",
                125_000L,
                "MXN",
                2,
                "CAPTURED",
                null,
                null,
                null,
                Instant.parse("2026-11-28T19:00:00Z"),
                Instant.parse("2026-11-28T19:00:00Z"),
                RECEIVED_AT,
                "ch",
                "0".repeat(64));
    }
}
