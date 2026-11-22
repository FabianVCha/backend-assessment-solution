package com.meridian.payments.intake.ops;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.StoreUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The operator HTTP boundary; never reads webhook headers or forwards client-supplied identity. */
public final class OperationsRouter implements HttpHandler {
    public static final String ROOT = "/ops/v1";
    private static final String NOTIFICATIONS = ROOT + "/notifications";
    private static final String OVERVIEW = ROOT + "/overview";
    private static final String REPLAYS = ROOT + "/replays";
    private static final int MAX_BODY = 8192;
    private static final Set<String> FILTERS = Set.of("provider", "country", "event_id", "payment_key", "limit", "cursor");
    private static final Set<String> REPLAY_FIELDS = Set.of("provider", "event_id", "destination", "reason",
            "idempotency_key");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final OpsIdentity identity;
    private final OperationsStore store;

    public OperationsRouter(OpsIdentity identity, OperationsStore store) {
        this.identity = identity;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = route(exchange);
            } catch (IllegalArgumentException e) {
                response = error(400, "INVALID_REQUEST");
            } catch (StoreUnavailableException e) {
                exchange.getResponseHeaders().set("Retry-After", "5");
                response = error(503, "STORE_UNAVAILABLE");
            }
            byte[] body = JSON.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        var authorization = exchange.getRequestHeaders().get("Authorization");
        var caller = identity.resolve(authorization != null && authorization.size() == 1 ? authorization.getFirst() : null);
        if (caller.isEmpty()) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return error(401, "UNAUTHENTICATED");
        }
        String path = exchange.getRequestURI().getRawPath();
        boolean replays = path.equals(REPLAYS);
        boolean detail = path.startsWith(NOTIFICATIONS + "/");
        if (!replays && !detail && !path.equals(NOTIFICATIONS) && !path.equals(OVERVIEW)) {
            return error(404, "NOT_FOUND");
        }
        String method = replays ? "POST" : "GET";
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            return error(405, "METHOD_NOT_ALLOWED");
        }
        var operator = caller.get();
        if (!operator.roles().contains(replays ? OpsIdentity.Role.replay : OpsIdentity.Role.read)) {
            return error(403, "FORBIDDEN");
        }
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (replays) {
            query(rawQuery, Set.of());
            return replay(exchange, operator);
        }
        if (detail) {
            query(rawQuery, Set.of());
            var parts = path.substring(NOTIFICATIONS.length() + 1).split("/", -1);
            if (parts.length != 2) {
                return error(404, "NOT_FOUND");
            }
            String provider = Provider.valueOf(decodePath(parts[0])).name();
            String eventID = value(decodePath(parts[1]), 512);
            return store.detail(operator.countries(), provider, eventID)
                    .map(OperationsRouter::ok).orElseGet(() -> error(404, "NOT_FOUND"));
        }
        boolean overview = path.equals(OVERVIEW);
        var params = query(rawQuery, overview ? Set.of("country") : FILTERS);
        var countries = operator.countries();
        if (params.containsKey("country")) {
            String country = params.get("country");
            if (!OpsIdentity.COUNTRIES.contains(country)) {
                throw new IllegalArgumentException("Unknown country");
            }
            if (!countries.contains(country)) {
                return error(403, "FORBIDDEN");
            }
            countries = Set.of(country);
        }
        if (overview) {
            return ok(store.overview(countries));
        }
        return list(countries, params);
    }

    private Response list(Set<String> countries, Map<String, String> params) {
        String rawLimit = params.getOrDefault("limit", "25");
        if (!rawLimit.matches("[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("Invalid limit");
        }
        int limit = Integer.parseInt(rawLimit);
        if (limit > 100) {
            throw new IllegalArgumentException("Invalid limit");
        }
        String provider = params.get("provider");
        var page = store.list(countries, new OperationsStore.Search(
                provider == null ? null : Provider.valueOf(provider).name(),
                optionalValue(params, "event_id"), optionalValue(params, "payment_key"), limit,
                decodeCursor(params.get("cursor"))));
        var response = JSON.createObjectNode();
        response.set("items", page.items());
        response.put("next_cursor", encodeCursor(page.next()));
        return ok(response);
    }

    private Response replay(HttpExchange exchange, OpsIdentity.Caller operator) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            return error(415, "UNSUPPORTED_MEDIA_TYPE");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            return error(413, "PAYLOAD_TOO_LARGE");
        }
        JsonNode request;
        try {
            request = JSON.readTree(bytes);
        } catch (IOException e) {
            return error(400, "INVALID_REQUEST");
        }
        if (request == null || !request.isObject() || request.size() != REPLAY_FIELDS.size()
                || REPLAY_FIELDS.stream().anyMatch(field -> !request.path(field).isTextual())) {
            return error(400, "INVALID_REQUEST");
        }
        String provider = Provider.valueOf(request.get("provider").textValue()).name();
        String destination = Destination.valueOf(request.get("destination").textValue()).name();
        var outcome = store.replay(operator, provider, value(request.get("event_id").textValue(), 512),
                destination, value(request.get("reason").textValue(), 1000),
                value(request.get("idempotency_key").textValue(), 128));
        return switch (outcome) {
            case REQUEUED -> ok(JSON.createObjectNode().put("outcome", outcome.name()));
            case EVENT_NOT_FOUND, JOB_NOT_FOUND -> error(404, "NOT_FOUND");
            case KEY_TARGET_CONFLICT, JOB_CLAIMED -> error(409, outcome.name());
            default -> error(400, "INVALID_REQUEST");
        };
    }

    private static Map<String, String> query(String raw, Set<String> allowed) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.length() > 4096) {
            throw new IllegalArgumentException("Query too long");
        }
        var result = new HashMap<String, String>();
        for (String pair : raw.split("&", -1)) {
            var parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length != 2 || !allowed.contains(key)
                    || result.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException("Invalid query parameter");
            }
        }
        return result;
    }

    private static String optionalValue(Map<String, String> params, String key) {
        var raw = params.get(key);
        return raw == null ? null : value(raw, 512);
    }

    private static String value(String text, int max) {
        if (text.isBlank() || text.length() > max
                || text.chars().anyMatch(c -> Character.isISOControl(c) || c == '\ufffd')) {
            throw new IllegalArgumentException("Invalid string parameter");
        }
        return text;
    }

    private static String decodePath(String segment) {
        return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String encodeCursor(OperationsStore.Position position) {
        if (position == null) return null;
        String text = position.receivedAt() + "/" + position.intakeId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static OperationsStore.Position decodeCursor(String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 128) throw new IllegalArgumentException("Invalid cursor");
        var text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        var parts = text.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
        try {
            var position = new OperationsStore.Position(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            if (position.receivedAt().getYear() < 1 || position.receivedAt().getYear() > 9999
                    || !cursor.equals(encodeCursor(position))) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return position;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }

    private static Response ok(JsonNode body) {
        return new Response(200, body);
    }

    private static Response error(int status, String code) {
        var body = JSON.createObjectNode();
        body.putObject("error").put("code", code);
        return new Response(status, body);
    }

    private record Response(int status, JsonNode body) {}
}
