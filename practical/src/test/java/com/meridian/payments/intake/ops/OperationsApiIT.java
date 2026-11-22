package com.meridian.payments.intake.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.NotificationIntake;
import com.meridian.payments.intake.auth.AndinaSignatureVerifier;
import com.meridian.payments.intake.auth.AustralSignatureVerifier;
import com.meridian.payments.intake.auth.Fixtures;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.QuetzalSignatureVerifier;
import com.meridian.payments.intake.canonical.AndinaCanonicalEventMapper;
import com.meridian.payments.intake.canonical.AustralCanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.http.IntakeServer;
import com.meridian.payments.intake.http.WebhookEndpoint;
import com.meridian.payments.intake.publish.CanonicalEventPublisher;
import com.meridian.payments.intake.salesforce.SalesforceClient;
import com.meridian.payments.intake.salesforce.SimulatedSalesforce;
import com.meridian.payments.intake.store.Database;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.PostgresIntakeStore;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperationsApiIT extends ReplayPostgresFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-21T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ROOT = "/ops/v1";
    private static final String LIST = "/notifications";
    private static final byte[] KEY = new byte[32]; // Test fixture only; never a configured local key.

    @Test
    void deniesAnonymousInvalidTokensAndWrongRolesBeforeReadingOrWriting() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("co", "one", "policy", "CAPTURED", NOW);
            var read = token("read", "CO");
            var replay = token("replay", "CO");
            error(401, "UNAUTHENTICATED", request(server, LIST, null, null));
            error(401, "UNAUTHENTICATED", request(server, LIST, read.substring(0, read.lastIndexOf('.') + 1)
                    + "A".repeat(43), null));
            for (String invalid : List.of(
                    token("read", "CO", "none", "issuer", "audience", NOW.getEpochSecond() + 60),
                    token("read", "CO", "HS256", "other", "audience", NOW.getEpochSecond() + 60),
                    token("read", "CO", "HS256", "issuer", "other", NOW.getEpochSecond() + 60),
                    token("read", "CO", "HS256", "issuer", "audience", NOW.getEpochSecond()),
                    token("admin", "CO"), token("read", "ZZ"))) {
                error(401, "UNAUTHENTICATED", request(server, LIST, invalid, null));
            }
            error(403, "FORBIDDEN", request(server, LIST, replay, null));
            error(403, "FORBIDDEN", request(server, "/replays", read, replayBody("ANDINA", "co", "k1")));
            assertEquals(0, count("replay_command"));
            assertEquals("PENDING/-/0", job("co", Destination.SALESFORCE));
            assertEquals("co", body(200, request(server, LIST, read, null)).at("/items/0/event_id").asText());
        }
    }

    @Test
    void foreignRowsAreInvisibleInSearchDetailsOverviewAndReplay() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("co", "one", "policy", "CAPTURED", NOW);
            seedMx(database);
            String read = token("read", "CO");
            assertEquals(1, body(200, request(server, LIST, read, null)).get("items").size());
            assertEquals(0, body(200, request(server, LIST + "?provider=QUETZAL&event_id=mx", read, null))
                    .get("items").size());
            error(403, "FORBIDDEN", request(server, LIST + "?country=MX", read, null));
            error(403, "FORBIDDEN", request(server, "/overview?country=MX", read, null));
            assertEquals(1, body(200, request(server, "/overview", read, null)).get("accepted").asInt());
            var foreign = request(server, LIST + "/QUETZAL/mx", read, null);
            var missing = request(server, LIST + "/QUETZAL/missing", read, null);
            error(404, "NOT_FOUND", foreign);
            assertEquals(missing.body(), foreign.body());
            String replay = token("replay", "CO");
            var denied = request(server, "/replays", replay, replayBody("QUETZAL", "mx", "foreign"));
            var absent = request(server, "/replays", replay, replayBody("QUETZAL", "missing", "foreign"));
            error(404, "NOT_FOUND", denied);
            assertEquals(absent.body(), denied.body());
            assertEquals(0, count("replay_command"));
            assertEquals("PENDING/-/0", job("mx", Destination.SALESFORCE));
        }
    }

    @Test
    void validatesFiltersAndPaginatesTiedTimesWithoutRepeatingRows() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("a", "one", "policy", "CAPTURED", NOW);
            accept("b", "one", "policy", "CAPTURED", NOW);
            accept("c", "two", "policy", "CAPTURED", NOW);
            String read = token("read", "CO");
            for (String query : List.of("limit=0", "limit=101", "limit=-1", "limit=1&limit=2", "offset=1",
                    "cursor=invalid", "provider=UNKNOWN", "country=ZZ", "event_id=", "payment_key=%00")) {
                error(400, "INVALID_REQUEST", request(server, LIST + "?" + query, read, null));
            }
            var a = body(200, request(server, LIST + "?payment_key=AN-CO-one&limit=1", read, null));
            var b = body(200, request(server, LIST + "?payment_key=AN-CO-one&limit=1&cursor="
                    + a.get("next_cursor").asText(), read, null));
            assertEquals(1, a.get("items").size());
            assertEquals(1, b.get("items").size());
            assertEquals(java.util.Set.of("a", "b"), java.util.Set.of(a.at("/items/0/event_id").asText(),
                    b.at("/items/0/event_id").asText()));
            assertTrue(b.get("next_cursor").isNull());
        }
    }

    @Test
    void distinguishesEmptyOverviewFromUnavailableAndCountsDuplicates() throws Exception {
        try (var database = open(); var server = start(database)) {
            String read = token("read", "CO");
            var empty = body(200, request(server, "/overview?country=CO", read, null));
            assertEquals(0, empty.get("accepted").asLong());
            assertEquals(0, empty.get("duplicates").asLong());
            assertEquals(0, empty.get("unresolved_work").asLong());
            assertEquals(0, empty.get("oldest_pending_age_seconds").asLong());
            assertTrue(empty.get("last_update_at").isNull());
            var intake = new PostgresIntakeStore(database, CLOCK);
            var event = event(Provider.ANDINA, "CO", "duplicate", "AN-CO-repeat", "COP", 2);
            assertEquals(com.meridian.payments.intake.store.Acceptance.ACCEPTED, intake.accept(event));
            assertEquals(com.meridian.payments.intake.store.Acceptance.DUPLICATE, intake.accept(event));
            var overview = body(200, request(server, "/overview?country=CO", read, null));
            assertEquals(1, overview.get("accepted").asLong());
            assertEquals(1, overview.get("duplicates").asLong());
            assertEquals(2, overview.get("unresolved_work").asLong());
            assertEquals(0, overview.get("dead").asLong());
            assertEquals(0, overview.get("held").asLong());
            assertTrue(overview.get("last_update_at").isTextual());
            database.close();
            error(503, "STORE_UNAVAILABLE", request(server, "/overview", read, null));
        }
    }

    @Test
    void presentsDeadAndRetryTimelinesWithSeparateEventAndProcessingTimes() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("dead", "dead-payment", "policy", "CAPTURED", NOW);
            accept("retry", "retry-payment", "policy", "CAPTURED", NOW);
            execute("""
                    UPDATE outbox_job o SET state = 'DEAD', outcome_code = 'INVALID_FIELD',
                    last_error = 'PRIVATE_REMOTE_MESSAGE'
                    FROM payment_event e WHERE e.intake_id = o.intake_id AND e.event_id = 'dead'
                    AND o.destination = 'SALESFORCE'
                    """);
            execute("""
                    UPDATE outbox_job o SET state = 'PENDING', outcome_code = 'TIMEOUT', attempts = 2,
                    next_attempt_at = '2030-01-01T00:00:00Z', last_error = 'PRIVATE_REMOTE_MESSAGE'
                    FROM payment_event e WHERE e.intake_id = o.intake_id AND e.event_id = 'retry'
                    AND o.destination = 'SALESFORCE'
                    """);
            String read = token("read", "CO");
            var response = request(server, LIST + "/ANDINA/dead", read, null);
            assertFalse(response.body().contains("PRIVATE_REMOTE_MESSAGE"));
            var dead = body(200, response);
            assertEquals(2, dead.get("jobs").size());
            assertEquals("INVALID_FIELD", dead.at("/jobs/1/outcome_code").asText());
            assertEquals("CAPTURED", dead.at("/payment_projection/latest_status").asText());
            assertEquals("RECEIVED", dead.at("/timeline/0/type").asText());
            assertEquals(NOW, Instant.parse(dead.at("/timeline/0/event_time").asText()));
            assertEquals(NOW, Instant.parse(dead.at("/timeline/0/processing_time").asText()));
            assertEquals("DEAD", dead.at("/timeline/1/type").asText());
            assertEquals("INVALID_FIELD", dead.at("/timeline/1/outcome_code").asText());
            assertEquals(NOW, Instant.parse(dead.at("/timeline/1/event_time").asText()));
            assertTrue(Instant.parse(dead.at("/timeline/1/processing_time").asText()).isAfter(NOW));
            var retried = body(200, request(server, LIST + "/ANDINA/retry", read, null));
            assertEquals("PENDING_RETRY", retried.at("/timeline/1/type").asText());
            assertEquals("TIMEOUT", retried.at("/timeline/1/outcome_code").asText());
            assertEquals(2, retried.at("/jobs/1/attempts").asInt());
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"),
                    Instant.parse(retried.at("/jobs/1/next_attempt_at").asText()));
            assertTrue(Instant.parse(retried.at("/timeline/1/processing_time").asText())
                    .isBefore(Instant.parse(retried.at("/jobs/1/next_attempt_at").asText())));
        }
    }

    @Test
    void presentsPublishedAppliedStaleAndHeldMilestonesAfterWorkersComplete() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("old", "pair", "policy", "CAPTURED", NOW.minusSeconds(60));
            accept("new", "pair", "policy", "REFUNDED", NOW);
            accept("held", "separate", "policy", "CAPTURED", NOW);
            assertEquals(3, new CanonicalEventPublisher(new PostgresIntakeStore(database), message -> {}, 10)
                    .publishPending());
            var scripted = new SimulatedSalesforce().failOnce("AN-CO-separate", SalesforceClient.Result.LOST_RESPONSE);
            for (int i = 0; i < 3; i++) {
                relay(scripted).drain();
            }
            String read = token("read", "CO");
            for (var expected : Map.of("new", "SALESFORCE_APPLIED", "old", "STALE_SKIP", "held", "HELD").entrySet()) {
                var detail = body(200, request(server, LIST + "/ANDINA/" + expected.getKey(), read, null));
                var timeline = new ArrayList<String>();
                detail.get("timeline").forEach(item -> timeline.add(item.get("type").asText()));
                assertEquals(List.of("RECEIVED", "CANONICAL_PUBLISHED", expected.getValue()), timeline);
                assertEquals("PUBLISHED", detail.at("/jobs/0/outcome_code").asText());
            }
            var overview = body(200, request(server, "/overview", read, null));
            assertEquals(1, overview.get("held").asInt());
            assertEquals(1, overview.get("unresolved_work").asInt());
            assertEquals(0, overview.get("oldest_pending_age_seconds").asInt());
        }
    }

    @Test
    void claimedHttpReplayDoesNotWrite() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("busy", "pair", "policy", "CAPTURED", NOW);
            execute("UPDATE outbox_job SET state = 'CLAIMED' WHERE destination = 'SALESFORCE'");
            String sql = "SELECT md5(string_agg(to_jsonb(o)::text, ',' ORDER BY outbox_id)) FROM outbox_job o";
            String before = scalar(sql);
            var reply = request(server, "/replays", token("replay", "CO"),
                    replayBody("ANDINA", "busy", "case-17"));
            error(409, "JOB_CLAIMED", reply);
            assertEquals(0, count("replay_command"));
            assertEquals(before, scalar(sql));
        }
    }

    @Test
    void heldAndUnattemptedHttpReplayDoNotResetTheQueue() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("unknown", "pair", "policy", "CAPTURED", NOW);
            var replay = token("replay", "CO");
            error(409, "JOB_NOT_REPLAYABLE",
                    request(server, "/replays", replay, replayBody("ANDINA", "unknown", "case-18")));
            relay(new SimulatedSalesforce(SalesforceClient.Result.LOST_RESPONSE)).drain();
            error(409, "RECONCILIATION_REQUIRED",
                    request(server, "/replays", replay, replayBody("ANDINA", "unknown", "case-18")));
            assertEquals(0, count("replay_command"));
            assertEquals("HELD/LOST_RESPONSE/0", job("unknown", Destination.SALESFORCE));
        }
    }

    @Test
    void returnsCommittedReplayOutcomeAndIsolatesKeysByCountry() throws Exception {
        try (var database = open(); var server = start(database)) {
            accept("co", "one", "policy", "CAPTURED", NOW);
            seedMx(database);
            var mx = token("replay", "MX");
            var co = token("replay", "CO");
            execute("UPDATE outbox_job SET state = 'DEAD', outcome_code = 'INVALID_FIELD' "
                    + "WHERE destination = 'SALESFORCE'");
            assertEquals("REQUEUED", body(200, request(server, "/replays", mx,
                    replayBody("QUETZAL", "mx", "same"))).get("outcome").asText());
            var first = request(server, "/replays", co, replayBody("ANDINA", "co", "same"));
            assertEquals("REQUEUED", body(200, first).get("outcome").asText());
            execute("UPDATE outbox_job SET attempts = 1 WHERE destination = 'SALESFORCE'");
            assertEquals(first.body(), request(server, "/replays", co, replayBody("ANDINA", "co", "same")).body());
            assertEquals("PENDING/REPLAY/1", job("co", Destination.SALESFORCE));
            assertEquals("PENDING/-/0", job("co", Destination.CANONICAL_EVENT));
            assertEquals(2, count("replay_command"));
            assertEquals("ops-test/INC-0412/REQUEUED/SALESFORCE", scalar("""
                    SELECT requester || '/' || reason || '/' || outcome || '/' || destination
                    FROM replay_command WHERE idempotency_key = 'same' AND event_id = 'co'
                    """));
        }
    }

    private static Database open() {
        var env = jdbcEnvironment();
        return Database.open(env.get("INTAKE_JDBC_URL"), env.get("INTAKE_DB_USER"), env.get("INTAKE_DB_PASSWORD"));
    }

    private static IntakeServer start(Database database) throws Exception {
        var endpoint = new WebhookEndpoint(new NotificationIntake(Map.of(
                Provider.ANDINA, new AndinaSignatureVerifier(Fixtures.SECRETS, CLOCK),
                Provider.AUSTRAL, new AustralSignatureVerifier(Fixtures.SECRETS),
                Provider.QUETZAL, new QuetzalSignatureVerifier(Fixtures.SECRETS, CLOCK)),
                List.of(new AndinaCanonicalEventMapper(), new AustralCanonicalEventMapper(),
                        new QuetzalCanonicalEventMapper()),
                new PostgresIntakeStore(database), CLOCK));
        return IntakeServer.start(endpoint, 0, new OperationsRouter(
                new LocalOpsIdentity("issuer", "audience", KEY, CLOCK), new OperationsStore(database)));
    }

    private static void seedMx(Database database) {
        assertEquals(com.meridian.payments.intake.store.Acceptance.ACCEPTED,
                new PostgresIntakeStore(database).accept(event(Provider.QUETZAL, "MX", "mx", "QZ-MX-one", "MXN", 2)));
    }

    private static CanonicalEvent event(Provider provider, String country, String id, String key, String currency,
                                        int exponent) {
        return new CanonicalEvent(id, provider, country, key, "payment", "policy", 100L, currency, exponent,
                "CAPTURED", null, null, null, NOW, NOW, NOW, "correlation", "0".repeat(64));
    }

    private static long count(String table) throws Exception {
        return Long.parseLong(scalar("SELECT count(*) FROM " + table));
    }

    private static String replayBody(String provider, String eventId, String key) {
        return """
                {"provider":"%s","event_id":"%s","destination":"SALESFORCE","reason":"INC-0412","idempotency_key":"%s"}
                """.formatted(provider, eventId, key);
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> request(IntakeServer server, String path, String token, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + ROOT + path))
                .header("X-Country", "MX").header("X-Roles", "replay").header("X-Subject", "header-subject");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void error(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(code, body(status, response).at("/error/code").asText());
    }

    private static String token(String role, String country) throws Exception {
        return token(role, country, "HS256", "issuer", "audience", NOW.getEpochSecond() + 60);
    }

    private static String token(String role, String country, String alg, String issuer, String audience, long expiry)
            throws Exception {
        String header = JSON.writeValueAsString(Map.of("alg", alg));
        String claims = JSON.writeValueAsString(Map.of("iss", issuer, "aud", audience, "exp", expiry,
                "sub", "ops-test", "roles", List.of(role), "countries", List.of(country)));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String signed = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return signed + "." + encoder.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
    }
}
