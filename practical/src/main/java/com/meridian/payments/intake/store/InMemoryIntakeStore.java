package com.meridian.payments.intake.store;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory stand-in for the inbox and the outbox.
 *
 * <p>It reproduces the properties the design leans on, not the SQL: a unique
 * {@code (provider, event_id)}, a single transaction that writes the event with both jobs, and an
 * outbox drained per destination where a failed attempt is withheld until its next attempt time. The
 * transaction is simulated by staging into copies and publishing them only after every write would
 * have succeeded, so a failure injected in the middle leaves the store exactly as it was.
 *
 * <p>The retry schedule is the store's, as {@code SOLUTION_DESIGN.md} §5 places
 * {@code next_attempt_at} on the outbox row: a failure moves the row into the future, and
 * {@link #pending} returns only the rows that are due. That is what keeps a broken broker from
 * becoming a hot loop.
 *
 * <p>Not for production: no persistence, no lease or claim and no concurrency control beyond one lock.
 */
public final class InMemoryIntakeStore implements IntakeStore, OutboxStore {

    /** Longest wait between attempts; the design asks for backoff, not for an infinite curve. */
    private static final Duration MAXIMUM_BACKOFF = Duration.ofSeconds(60);

    private record EventKey(Provider provider, String eventId) {
    }

    private static final class JobState {
        private final CanonicalEvent event;
        private int attempts;
        private boolean published;
        private Instant nextAttemptAt;

        private JobState(CanonicalEvent event) {
            this.event = event;
        }
    }

    private final Object lock = new Object();
    private final Clock clock;
    private final Map<EventKey, CanonicalEvent> events = new LinkedHashMap<>();
    private final Map<OutboxJob, JobState> outbox = new LinkedHashMap<>();
    private boolean failBeforeCommit;
    private boolean failMidTransaction;

    public InMemoryIntakeStore() {
        this(Clock.systemUTC());
    }

    /** A store that reads time from the given clock; tests own the clock to assert the schedule. */
    public InMemoryIntakeStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Acceptance accept(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        EventKey key = new EventKey(event.provider(), event.eventId());
        synchronized (lock) {
            if (failBeforeCommit) {
                failBeforeCommit = false;
                throw new StoreUnavailableException("the store is unavailable");
            }
            if (events.containsKey(key)) {
                return Acceptance.DUPLICATE;
            }

            Map<EventKey, CanonicalEvent> stagedEvents = new LinkedHashMap<>(events);
            stagedEvents.put(key, event);
            Map<OutboxJob, JobState> stagedOutbox = new LinkedHashMap<>(outbox);
            stagedOutbox.put(new OutboxJob(event.provider(), event.eventId(), Destination.SALESFORCE),
                    new JobState(event));
            stagedOutbox.put(new OutboxJob(event.provider(), event.eventId(), Destination.CANONICAL_EVENT),
                    new JobState(event));

            if (failMidTransaction) {
                failMidTransaction = false;
                throw new StoreUnavailableException("the store failed after the inbox row");
            }

            events.clear();
            events.putAll(stagedEvents);
            outbox.clear();
            outbox.putAll(stagedOutbox);
            return Acceptance.ACCEPTED;
        }
    }

    @Override
    public List<PendingJob> pending(Destination destination, int limit) {
        Objects.requireNonNull(destination, "destination");
        Instant now = clock.instant();
        synchronized (lock) {
            return outbox.entrySet().stream()
                    .filter(entry -> entry.getKey().destination() == destination)
                    .filter(entry -> !entry.getValue().published)
                    .filter(entry -> entry.getValue().nextAttemptAt == null
                            || !entry.getValue().nextAttemptAt.isAfter(now))
                    .limit(limit)
                    .map(entry -> new PendingJob(entry.getKey(), entry.getValue().event))
                    .toList();
        }
    }

    @Override
    public void published(PendingJob job) {
        Objects.requireNonNull(job, "job");
        synchronized (lock) {
            state(job).published = true;
        }
    }

    /** Records the failure and moves the job into the future with a growing wait. */
    @Override
    public void failed(PendingJob job, String reason) {
        Objects.requireNonNull(job, "job");
        synchronized (lock) {
            JobState state = state(job);
            state.attempts++;
            state.nextAttemptAt = clock.instant().plus(backoff(state.attempts));
        }
    }

    /** Attempts recorded for one job, so a test can tell a failure from a lost job. */
    public int attempts(OutboxJob job) {
        synchronized (lock) {
            JobState state = outbox.get(job);
            return state == null ? 0 : state.attempts;
        }
    }

    /** When the job becomes due again, or {@code null} when it was never attempted. */
    public Instant nextAttemptAt(OutboxJob job) {
        synchronized (lock) {
            JobState state = outbox.get(job);
            return state == null ? null : state.nextAttemptAt;
        }
    }

    /** Makes the next accepted write fail before anything is staged. */
    public void failNextAcceptBeforeCommit() {
        synchronized (lock) {
            failBeforeCommit = true;
        }
    }

    /** Makes the next accepted write fail after the inbox row would have been written. */
    public void failNextAcceptMidTransaction() {
        synchronized (lock) {
            failMidTransaction = true;
        }
    }

    public int eventCount() {
        synchronized (lock) {
            return events.size();
        }
    }

    public List<OutboxJob> outboxJobs() {
        synchronized (lock) {
            return List.copyOf(outbox.keySet());
        }
    }

    private static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts - 1, 6);
        Duration candidate = Duration.ofSeconds(seconds);
        return candidate.compareTo(MAXIMUM_BACKOFF) > 0 ? MAXIMUM_BACKOFF : candidate;
    }

    private JobState state(PendingJob job) {
        JobState state = outbox.get(job.job());
        if (state == null) {
            throw new IllegalArgumentException("the job is not in this outbox");
        }
        return state;
    }
}
