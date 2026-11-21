package com.meridian.payments.intake.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.IntakeOutcome;
import com.meridian.payments.intake.NotificationIntake;
import com.meridian.payments.intake.WebhookRejectedException;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;
import com.meridian.payments.intake.canonical.CanonicalMappingException;
import com.meridian.payments.intake.store.Acceptance;
import com.meridian.payments.intake.store.StoreUnavailableException;

import java.util.Map;
import java.util.Objects;

/**
 * The HTTP surface of the two webhook paths published in {@code docs/api/webhooks.openapi.yaml}.
 *
 * <p>The status codes, the ordered checks and the two receipt shapes are the contract, not a choice
 * made here: size 413, signature 401, JSON and schema 400, store 503, and a 2xx only after the event
 * and both outbox jobs are durable. Andina accepts either {@code 200} or {@code 204} and gets a body
 * only on first acceptance; Quetzal expects any {@code 2xx} and gets a receipt either way.
 *
 * <p>Error bodies carry the contract code and a fixed sentence. Nothing echoes the request: not the
 * body, not a signature, not a provider identifier and not a policy number.
 */
public final class WebhookEndpoint {

    /** Both providers cap the request body at 64 KiB in the published contract. */
    static final int MAX_BODY_BYTES = 65_536;

    private static final Map<String, Provider> ROUTES = Map.of(
            "/webhooks/mx/quetzal", Provider.QUETZAL,
            "/webhooks/co/andina", Provider.ANDINA);

    private static final String SIGNATURE_MESSAGE = "The request signature could not be verified.";
    private static final String STORE_MESSAGE = "The notification could not be accepted. Retry.";
    private static final String TOO_LARGE_MESSAGE = "The request body exceeds 64 KiB.";

    private final NotificationIntake intake;
    private final ObjectMapper json = new ObjectMapper();

    public WebhookEndpoint(NotificationIntake intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
    }

    public HttpResponse handle(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        Provider provider = ROUTES.get(request.path());
        if (provider == null) {
            return error(404, "NOT_FOUND", "No webhook is published at this path.", false);
        }
        if (request.body().length > MAX_BODY_BYTES) {
            return error(413, "PAYLOAD_TOO_LARGE", TOO_LARGE_MESSAGE, false);
        }
        try {
            IntakeOutcome outcome = intake.handle(
                    new WebhookRequest(provider, request.headers(), request.body()));
            if (outcome.duplicate() && provider == Provider.ANDINA) {
                return HttpResponse.empty(204);
            }
            return receipt(outcome.acceptance());
        } catch (WebhookRejectedException e) {
            return error(401, e.failure().name(), SIGNATURE_MESSAGE, false);
        } catch (CanonicalMappingException e) {
            return error(400, e.failure().name(), mappingMessage(e.failure()), false);
        } catch (StoreUnavailableException e) {
            return error(503, "STORE_UNAVAILABLE", STORE_MESSAGE, true).withHeader("Retry-After", "5");
        } catch (RuntimeException e) {
            return error(500, "INTERNAL_ERROR", "Unexpected error.", true);
        }
    }

    private HttpResponse receipt(Acceptance acceptance) {
        ObjectNode body = json.createObjectNode();
        body.put("status", acceptance.name());
        return HttpResponse.json(200, body.toString());
    }

    private HttpResponse error(int status, String code, String message, boolean retryable) {
        ObjectNode error = json.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        ObjectNode body = json.createObjectNode();
        body.set("error", error);
        return HttpResponse.json(status, body.toString());
    }

    private static String mappingMessage(CanonicalMappingException.Failure failure) {
        return switch (failure) {
            case MALFORMED_JSON -> "The request body is not a JSON document.";
            case SCHEMA_VALIDATION_FAILED -> "The notification does not match the published contract.";
            case POLICY_REFERENCE_UNPARSEABLE -> "The merchant reference is not a policy number.";
        };
    }
}
