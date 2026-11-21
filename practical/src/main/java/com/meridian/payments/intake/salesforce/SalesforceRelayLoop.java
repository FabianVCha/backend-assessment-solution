package com.meridian.payments.intake.salesforce;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The tick that drains the Salesforce lane, with no human and no request in the loop.
 *
 * <p>Separate from the canonical publisher on purpose: the two lanes must not share a failure. A tick never
 * kills the loop — the relay already classifies every remote answer, and anything that escapes is logged
 * with a class name and the next tick tries again. A relay that stops on the first unexpected error would
 * leave payments unwritten with nobody watching.
 */
public final class SalesforceRelayLoop implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake.salesforce");

    private final SalesforceRelay relay;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private ScheduledFuture<?> tick;

    public SalesforceRelayLoop(SalesforceRelay relay, Duration interval) {
        this(relay, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salesforce-relay");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public SalesforceRelayLoop(
            SalesforceRelay relay, Duration interval, ScheduledExecutorService scheduler) {
        this(relay, interval, scheduler, false);
    }

    private SalesforceRelayLoop(
            SalesforceRelay relay, Duration interval, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.relay = Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(interval, "interval");
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("the poll interval must be at least one millisecond");
        }
        this.intervalMillis = interval.toMillis();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

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
            int applied = relay.drain();
            if (applied > 0) {
                LOG.info("applied " + applied + " payment facts");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "salesforce tick failed with " + e.getClass().getSimpleName());
        }
    }
}
