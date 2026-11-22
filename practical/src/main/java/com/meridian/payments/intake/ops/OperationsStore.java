package com.meridian.payments.intake.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.store.Database;
import com.meridian.payments.intake.store.StoreUnavailableException;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Scoped PostgreSQL reads and delegation to the existing replay transaction. */
public final class OperationsStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT = """
            jsonb_build_object(
                'provider', e.provider, 'country', e.canonical ->> 'country', 'event_id', e.event_id,
                'payment_key', e.payment_key, 'status', e.status, 'occurred_at', e.occurred_at,
                'status_changed_at', e.status_changed_at, 'received_at', e.received_at)
            """;
    private static final String SCOPE = "e.canonical ->> 'country' = ANY(?)";
    private static final String DETAIL = """
            SELECT %s AS event,
                   (SELECT to_jsonb(p) FROM payment_state p
                    WHERE p.payment_key = e.payment_key AND p.provider = e.provider
                      AND p.country = e.canonical ->> 'country') AS projection,
                   (SELECT jsonb_agg(jsonb_build_object(
                       'destination', o.destination, 'state', o.state, 'attempts', o.attempts,
                       'next_attempt_at', CASE WHEN o.state = 'PENDING' THEN o.next_attempt_at END,
                       'processing_time', coalesce(o.transitioned_at, o.published_at),
                       'outcome_code', CASE
                           WHEN o.destination = 'CANONICAL_EVENT' AND o.state = 'PUBLISHED'
                               THEN 'PUBLISHED'
                           WHEN o.destination = 'CANONICAL_EVENT' AND o.attempts > 0
                               THEN 'BROKER_RETRY'
                           ELSE o.outcome_code
                       END) ORDER BY o.destination)
                    FROM outbox_job o WHERE o.intake_id = e.intake_id) AS jobs
            FROM payment_event e WHERE %s AND e.provider = ? AND e.event_id = ?
            """.formatted(EVENT, SCOPE);
    private static final String OVERVIEW = """
            WITH visible AS (
                SELECT e.intake_id, e.received_at, e.duplicate_count, e.last_duplicate_at
                FROM payment_event e WHERE %s
            ), events AS (
                SELECT count(*) AS accepted, coalesce(sum(duplicate_count), 0) AS duplicates,
                       max(greatest(received_at, last_duplicate_at)) AS last_update
                FROM visible
            ), jobs AS (
                SELECT count(*) FILTER (WHERE o.state <> 'PUBLISHED') AS unresolved_work,
                       count(*) FILTER (WHERE o.state = 'DEAD') AS dead,
                       count(*) FILTER (WHERE o.state = 'HELD') AS held,
                       min(e.received_at) FILTER (WHERE o.state IN ('PENDING', 'CLAIMED')) AS oldest_pending,
                       max(greatest(o.transitioned_at, o.published_at)) AS last_update
                FROM visible e JOIN outbox_job o USING (intake_id)
            )
            SELECT jsonb_build_object(
                'accepted', events.accepted, 'duplicates', events.duplicates,
                'unresolved_work', jobs.unresolved_work, 'dead', jobs.dead, 'held', jobs.held,
                'oldest_pending_age_seconds', greatest(0,
                    floor(extract(epoch FROM statement_timestamp() - jobs.oldest_pending))),
                'last_update_at', greatest(events.last_update, jobs.last_update)) AS overview
            FROM events CROSS JOIN jobs
            """.formatted(SCOPE);

    private final Database database;
    private final ReplayService replay;

    public OperationsStore(Database database) {
        this.database = database;
        this.replay = new ReplayService(database);
    }

    record Position(OffsetDateTime receivedAt, UUID intakeId) {}

    record Search(String provider, String eventId, String paymentKey, int limit, Position after) {}

    record Page(ArrayNode items, Position next) {}

    Page list(Set<String> countries, Search search) {
        var sql = new StringBuilder("SELECT ").append(EVENT)
                .append(" AS event, e.received_at, e.intake_id FROM payment_event e WHERE ").append(SCOPE);
        if (search.provider() != null) {
            sql.append(" AND e.provider = ?");
        }
        if (search.eventId() != null) {
            sql.append(" AND e.event_id = ?");
        }
        if (search.paymentKey() != null) {
            sql.append(" AND e.payment_key = ?");
        }
        if (search.after() != null) {
            sql.append(" AND (e.received_at, e.intake_id) < (?, ?)");
        }
        sql.append(" ORDER BY e.received_at DESC, e.intake_id DESC LIMIT ?");
        return read(sql.toString(), countries, statement -> {
            int i = 2;
            if (search.provider() != null) statement.setString(i++, search.provider());
            if (search.eventId() != null) statement.setString(i++, search.eventId());
            if (search.paymentKey() != null) statement.setString(i++, search.paymentKey());
            if (search.after() != null) {
                statement.setObject(i++, search.after().receivedAt());
                statement.setObject(i++, search.after().intakeId());
            }
            statement.setInt(i, search.limit() + 1);
        }, rows -> {
            var items = JSON.createArrayNode();
            Position last = null;
            while (rows.next()) {
                if (items.size() == search.limit()) {
                    return new Page(items, last);
                }
                items.add(node(rows, "event"));
                last = new Position(rows.getObject("received_at", OffsetDateTime.class),
                        rows.getObject("intake_id", UUID.class));
            }
            return new Page(items, null);
        });
    }

    Optional<ObjectNode> detail(Set<String> countries, String provider, String eventId) {
        return read(DETAIL, countries, statement -> {
            statement.setString(2, provider);
            statement.setString(3, eventId);
        }, rows -> {
            if (!rows.next()) {
                return Optional.empty();
            }
            JsonNode event = node(rows, "event");
            JsonNode jobs = node(rows, "jobs");
            var timeline = JSON.createArrayNode();
            timeline.add(moment("RECEIVED", event.get("occurred_at"), event.get("received_at")));
            var transitions = new ArrayList<JsonNode>();
            jobs.forEach(transitions::add);
            transitions.sort(Comparator.comparing(OperationsStore::processingTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (var job : transitions) {
                String type = milestone(job);
                if (type != null) {
                    timeline.add(moment(type, event.get("occurred_at"), job.get("processing_time"))
                            .set("outcome_code", job.get("outcome_code")));
                }
            }
            var result = JSON.createObjectNode();
            result.set("event", event);
            result.set("jobs", jobs);
            result.set("payment_projection", node(rows, "projection"));
            result.set("timeline", timeline);
            return Optional.of(result);
        });
    }

    private static OffsetDateTime processingTime(JsonNode job) {
        var time = job.get("processing_time");
        return time.isNull() ? null : OffsetDateTime.parse(time.asText());
    }

    private static ObjectNode moment(String type, JsonNode eventTime, JsonNode processingTime) {
        var result = JSON.createObjectNode().put("type", type);
        result.set("event_time", eventTime);
        result.set("processing_time", processingTime);
        return result;
    }

    private static String milestone(JsonNode job) {
        String outcome = job.path("outcome_code").asText("");
        return switch (job.get("state").asText()) {
            case "PUBLISHED" -> switch (job.get("destination").asText()) {
                case "CANONICAL_EVENT" -> "CANONICAL_PUBLISHED";
                default -> switch (outcome) {
                    case "APPLIED" -> "SALESFORCE_APPLIED";
                    case "STALE_SKIPPED" -> "STALE_SKIP";
                    default -> null;
                };
            };
            case "PENDING" -> !outcome.isEmpty() && !"REPLAY".equals(outcome) ? "PENDING_RETRY" : null;
            case "DEAD" -> "DEAD";
            case "HELD" -> "HELD";
            default -> null;
        };
    }

    JsonNode overview(Set<String> countries) {
        return read(OVERVIEW, countries, statement -> {}, rows -> {
            rows.next();
            return node(rows, "overview");
        });
    }

    ReplayService.Outcome replay(OpsIdentity.Caller caller, String provider, String eventId, String destination,
                                 String reason, String key) {
        return replay.replayScoped(caller, provider, eventId, destination, reason, key);
    }

    private static JsonNode node(ResultSet rows, String column) throws SQLException, IOException {
        String value = rows.getString(column);
        return value == null ? JSON.nullNode() : JSON.readTree(value);
    }

    private <T> T read(String sql, Set<String> countries, Bind bind, Read<T> read) {
        try (var connection = database.connection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);
                statement.setArray(1, connection.createArrayOf("text", countries.toArray()));
                bind.apply(statement);
                try (var rows = statement.executeQuery()) {
                    return read.apply(rows);
                }
            }
        } catch (SQLException | IOException e) {
            throw new StoreUnavailableException("Operations data is unavailable", e);
        }
    }

    @FunctionalInterface
    private interface Bind {
        void apply(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Read<T> {
        T apply(ResultSet rows) throws SQLException, IOException;
    }
}
