package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;

import java.time.Instant;

/**
 * Reads back the canonical document this system stores and publishes.
 *
 * <p>Publication must send what was accepted, not something rebuilt from columns later. The event is
 * therefore persisted as the wire document and parsed here, and a round-trip test asserts that what
 * comes out equals what went in: if the two ever diverge, a consumer would receive a different fact
 * from the one the provider was acknowledged for.
 */
public final class CanonicalEvents {

    private CanonicalEvents() {
    }

    public static CanonicalEvent fromJson(ObjectNode json) {
        String version = text(json, "schema_version");
        if (!CanonicalEvent.SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported schema_version " + version);
        }
        JsonNode refunded = json.get("refunded_amount_minor");
        return new CanonicalEvent(
                text(json, "event_id"),
                Provider.valueOf(text(json, "provider")),
                text(json, "country"),
                text(json, "payment_key"),
                text(json, "provider_transaction_id"),
                text(json, "policy_number"),
                json.get("amount_minor").longValue(),
                text(json, "currency"),
                json.get("currency_exponent").intValue(),
                text(json, "status"),
                refunded == null || refunded.isNull() ? null : refunded.longValue(),
                nullableText(json, "refunded_amount_semantics"),
                nullableText(json, "refund_scope"),
                Instant.parse(text(json, "status_changed_at")),
                Instant.parse(text(json, "occurred_at")),
                Instant.parse(text(json, "received_at")),
                text(json, "correlation_id"),
                text(json, "payload_hash"));
    }

    private static String text(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing field " + field + " in the stored canonical event");
        }
        return value.asText();
    }

    /**
     * A field that is present in every document but may be {@code null}.
     *
     * <p>{@code refunded_amount_semantics} is null for a payment nobody has refunded, and the contract
     * says so explicitly: absent-as-null is a fact, and rejecting it here made the first real provider
     * sample fail to publish after its round trip.
     */
    private static String nullableText(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
