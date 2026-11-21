package com.meridian.payments.intake.store;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    private record EventKey(Provider provider, String eventId) {
    }

    private static final class JobState {
        private final CanonicalEvent event;
        private int attempts;
        private boolean published;
        private Instant nextAttemptAt;
        private String claimToken;
        private String claimOwner;
        private Instant claimExpiresAt;

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
    public List<PendingJob> claim(Destination destination, int limit, String owner, Duration lease) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a lease must last longer than nothing");
        }
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        synchronized (lock) {
            List<PendingJob> claimed = new ArrayList<>();
            for (Map.Entry<OutboxJob, JobState> entry : outbox.entrySet()) {
                if (entry.getKey().destination() != destination) {
                    continue;
                }
                JobState state = entry.getValue();
                if (state.published || heldByALiveWorker(state, now) || notDue(state, now)) {
                    continue;
                }
                state.claimToken = token;
                state.claimOwner = owner;
                state.claimExpiresAt = now.plus(lease);
                claimed.add(new PendingJob(entry.getKey(), state.event, token));
                if (claimed.size() == limit) {
                    break;
                }
            }
            return claimed;
        }
    }

    private static boolean heldByALiveWorker(JobState state, Instant now) {
        return state.claimToken != null && state.claimExpiresAt != null && state.claimExpiresAt.isAfter(now);
    }

    private static boolean notDue(JobState state, Instant now) {
        return state.nextAttemptAt != null && state.nextAttemptAt.isAfter(now);
    }

    @Override
    public void published(PendingJob job) {
        Objects.requireNonNull(job, "job");
        synchronized (lock) {
            JobState state = claimedState(job);
            state.published = true;
            clearClaim(state);
        }
    }

    /** Records the failure and moves the job into the future with a growing wait. */
    @Override
    public void failed(PendingJob job, String reason) {
        Objects.requireNonNull(job, "job");
        synchronized (lock) {
            JobState state = claimedState(job);
            state.attempts++;
            state.nextAttemptAt = clock.instant().plus(backoff(state.attempts));
            clearClaim(state);
        }
    }

    private JobState claimedState(PendingJob job) {
        if (!job.claimed()) {
            throw new IllegalArgumentException("a job must be claimed before it can be completed");
        }
        JobState state = state(job);
        if (!job.claimToken().equals(state.claimToken)) {
            throw new ClaimLostException("the lease on this job belongs to another worker now");
        }
        return state;
    }

    private static void clearClaim(JobState state) {
        state.claimToken = null;
        state.claimOwner = null;
        state.claimExpiresAt = null;
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
        return RetrySchedule.after(attempts);
    }

    private JobState state(PendingJob job) {
        JobState state = outbox.get(job.job());
        if (state == null) {
            throw new IllegalArgumentException("the job is not in this outbox");
        }
        return state;
    }
}
