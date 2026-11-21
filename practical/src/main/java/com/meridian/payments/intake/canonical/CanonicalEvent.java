package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * The canonical payment event published for downstream consumers (FR2, FR6).
 *
 * <p>Field names, order and semantics are fixed by
 * {@code docs/api/events/premium-payment.schema.json}. That document is the contract and this
 * record is its only producer in Part B, which is why the event is closed: an accidental field is
 * a failure here, not silent drift for a consumer.
 *
 * <p>Every schema field is always present. An absent refund is written as an explicit JSON
 * {@code null} and never omitted or zero-filled, because "the provider reported no amount" and
 * "the provider reported zero" are different facts.
 */
public record CanonicalEvent(
        String eventId,
        Provider provider,
        String country,
        String paymentKey,
        String providerTransactionId,
        String policyNumber,
        long amountMinor,
        String currency,
        int currencyExponent,
        String status,
        Long refundedAmountMinor,
        String refundedAmountSemantics,
        String refundScope,
        Instant statusChangedAt,
        Instant occurredAt,
        Instant receivedAt,
        String correlationId,
        String payloadHash) {

    /** Version of the event contract this producer writes. */
    public static final String SCHEMA_VERSION = "1.0";

    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;

    public CanonicalEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        Objects.requireNonNull(policyNumber, "policyNumber");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payloadHash, "payloadHash");
    }

    /** The event as the wire document, with all nineteen properties present. */
    public ObjectNode toJson() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode json = nodes.objectNode();
        json.put("schema_version", SCHEMA_VERSION);
        json.put("event_id", eventId);
        json.put("provider", provider.name());
        json.put("country", country);
        json.put("payment_key", paymentKey);
        json.put("provider_transaction_id", providerTransactionId);
        json.put("policy_number", policyNumber);
        json.put("amount_minor", amountMinor);
        json.put("currency", currency);
        json.put("currency_exponent", currencyExponent);
        json.put("status", status);
        json.put("refunded_amount_minor", refundedAmountMinor == null ? null : refundedAmountMinor);
        json.put("refunded_amount_semantics", refundedAmountSemantics);
        json.put("refund_scope", refundScope);
        json.put("status_changed_at", UTC.format(statusChangedAt));
        json.put("occurred_at", UTC.format(occurredAt));
        json.put("received_at", UTC.format(receivedAt));
        json.put("correlation_id", correlationId);
        json.put("payload_hash", payloadHash);
        return json;
    }
}
