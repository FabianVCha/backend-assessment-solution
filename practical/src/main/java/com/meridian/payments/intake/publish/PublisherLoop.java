package com.meridian.payments.intake.publish;

import com.meridian.payments.intake.store.PendingJob;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The polling role the design calls the event publisher ({@code SOLUTION_DESIGN.md} §1.2, §5).
 *
 * <p>`CanonicalEventPublisher` publishes one batch; something has to ask it, repeatedly, without a
 * human or a test in the loop. That is this class: a fixed-delay tick over the outbox.
 *
 * <p>A tick never kills the loop. The publisher already turns a broker failure into a pending job with
 * a scheduled retry, and anything else that escapes is logged and the next tick tries again: a
 * publisher that stops on the first unexpected error would silently stop publishing payments.
 *
 * <p>Log lines carry a count only. No provider identifier, policy number or payload.
 */
public final class PublisherLoop implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.publish");

    private final CanonicalEventPublisher publisher;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private ScheduledFuture<?> tick;

    /** Creates a loop with its own single-thread, daemon scheduler. */
    public PublisherLoop(CanonicalEventPublisher publisher, Duration interval) {
        this(publisher, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "canonical-event-publisher");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    /** Creates a loop over a scheduler the caller owns; closing the loop does not shut it down. */
    public PublisherLoop(CanonicalEventPublisher publisher, Duration interval, ScheduledExecutorService scheduler) {
        this(publisher, interval, scheduler, false);
    }

    private PublisherLoop(
            CanonicalEventPublisher publisher,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(interval, "interval");
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("the poll interval must be at least one millisecond");
        }
        this.intervalMillis = interval.toMillis();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    /** Starts ticking. The first tick runs immediately, so a restart does not wait a full interval. */
    public synchronized void start() {
        if (tick != null) {
            throw new IllegalStateException("the loop is already running");
        }
        tick = scheduler.scheduleWithFixedDelay(this::runTick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void runTick() {
        try {
            int published = publisher.publishPending();
            if (published > 0) {
                LOG.info("published " + published + " canonical events");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "publish tick failed with " + e.getClass().getSimpleName());
        }
    }
}
