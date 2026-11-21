package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.TestSamples;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.InMemoryIntakeStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The polling role, without a test calling the publisher by hand.
 *
 * <p>Both tests are latch-driven instead of sleep-driven: the loop either publishes within the timeout
 * or the test fails, so a slow machine cannot turn a broken loop into a passing test.
 */
class PublisherLoopTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-12-03T15:00:00Z");
    private static final Duration INTERVAL = Duration.ofMillis(20);

    private final InMemoryIntakeStore store = new InMemoryIntakeStore();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Test
    void theLoopPublishesWithoutAnyoneCallingThePublisher() throws InterruptedException {
        CanonicalEvent event = acceptQuetzalSample();
        CountDownLatch published = new CountDownLatch(1);
        List<CanonicalMessage> messages = new CopyOnWriteArrayList<>();
        Broker broker = message -> {
            messages.add(message);
            published.countDown();
        };

        try (PublisherLoop loop = new PublisherLoop(
                new CanonicalEventPublisher(store, broker, 10), INTERVAL, scheduler)) {
            loop.start();

            assertTrue(published.await(5, TimeUnit.SECONDS), "the loop must publish on its own tick");
            // The latch fires inside broker.publish, one instruction before the store marks the job
            // published. A loaded runner loses that race and the assertion below sees the job still
            // pending. Wait for the state the invariant is about, not for the broker call, while the
            // loop is still alive to finish the tick it started.
            awaitEmptyOutbox(5);
        }

        assertEquals(1, messages.size());
        assertEquals("QUETZAL:" + event.eventId(), messages.get(0).messageId());
    }

    /** Waits, with a deadline, until the canonical outbox has nothing due. */
    private void awaitEmptyOutbox(int seconds) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(seconds);
        while (!store.pending(Destination.CANONICAL_EVENT, 10).isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                fail("the published job is still pending after " + seconds + " seconds");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void aBrokerFailureDoesNotStopTheLoop() throws InterruptedException {
        acceptQuetzalSample();
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Broker flaky = message -> {
            if (calls.getAndIncrement() == 0) {
                throw new BrokerUnavailableException("the broker is down");
            }
            published.countDown();
        };

        try (PublisherLoop loop = new PublisherLoop(
                new CanonicalEventPublisher(store, flaky, 10), INTERVAL, scheduler)) {
            loop.start();

            assertTrue(published.await(10, TimeUnit.SECONDS),
                    "the loop must keep ticking and publish once the retry is due");
        }

        assertTrue(calls.get() >= 2, "the failed attempt was retried by the loop itself");
    }

    private CanonicalEvent acceptQuetzalSample() {
        CanonicalEvent event = new QuetzalCanonicalEventMapper().map(
                new WebhookRequest(Provider.QUETZAL, Map.of(), TestSamples.read(TestSamples.QZ_SUCCEEDED)),
                RECEIVED_AT);
        store.accept(event);
        return event;
    }
}
