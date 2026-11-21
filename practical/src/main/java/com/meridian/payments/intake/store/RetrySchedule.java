package com.meridian.payments.intake.store;

import java.time.Duration;

/**
 * The retry curve, stated once so both stores schedule the same thing.
 *
 * <p>One second, doubling, capped at one minute: a broker that is down must not become a hot loop, and
 * a job must not wait an hour because it failed once. The SQL in {@code V1__intake.sql} implements the
 * same curve inside the database; an integration test pins the two together, which is the only reason
 * the duplication is acceptable.
 */
public final class RetrySchedule {

    /** Longest wait between attempts. */
    public static final Duration MAXIMUM = Duration.ofSeconds(60);

    private RetrySchedule() {
    }

    /** The wait after the given failed attempt, counting from one. */
    public static Duration after(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("an attempt count starts at one");
        }
        Duration candidate = Duration.ofSeconds(1L << Math.min(attempts - 1, 6));
        return candidate.compareTo(MAXIMUM) > 0 ? MAXIMUM : candidate;
    }
}
