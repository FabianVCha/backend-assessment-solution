package com.meridian.payments.intake.ops;

import com.meridian.payments.intake.store.Database;

import java.io.PrintStream;
import java.util.Map;

/** Local operator entry point. It queues work; it never contacts either destination itself. */
public final class ReplayCli {

    private ReplayCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.getenv(), System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 6) {
            err.println("Usage: ReplayCli <provider> <event-id> <destination> <reason> <requester> <idempotency-key>");
            return 2;
        }
        String url = environment(env, "INTAKE_JDBC_URL", "");
        if (url.isEmpty()) {
            err.println("INTAKE_JDBC_URL is required");
            return 2;
        }
        try (Database database = Database.open(url, environment(env, "INTAKE_DB_USER", "intake"),
                environment(env, "INTAKE_DB_PASSWORD", ""))) {
            var outcome = new ReplayService(database).replay(args[0], args[1], args[2], args[3], args[4], args[5]);
            out.println(outcome.name());
            return outcome == ReplayService.Outcome.REQUEUED ? 0 : 1;
        } catch (RuntimeException e) {
            // Do not echo driver details or credentials. A retry with the same key also covers
            // a connection that dropped after the database committed.
            err.println("REPLAY_UNAVAILABLE");
            return 2;
        }
    }

    private static String environment(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
