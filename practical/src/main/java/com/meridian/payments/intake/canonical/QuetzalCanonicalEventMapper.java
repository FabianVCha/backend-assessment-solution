package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Quetzal Pagos (MX) 2025-06-01 notification to canonical event.
 *
 * <p>Identity comes from {@code id}, which the provider keeps stable across retries. Amounts already
 * arrive in the currency's minor unit, so the only normalisation here is the currency casing.
 */
public final class QuetzalCanonicalEventMapper implements CanonicalEventMapper {

    private static final String COUNTRY = "MX";
    private static final String CURRENCY = "MXN";

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public CanonicalEvent map(WebhookRequest request, Instant receivedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (request.provider() != Provider.QUETZAL) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "the request does not belong to Quetzal");
        }

        ObjectNode payload = Payloads.parse(request.rawBody());
        String eventId = Payloads.requiredText(payload, "id", "$");
        String type = Payloads.requiredText(payload, "type", "$");
        long created = Payloads.requiredInteger(payload, "created", "$", 1, Long.MAX_VALUE);
        JsonNode data = Payloads.requiredObject(payload, "data", "$");
        JsonNode charge = Payloads.requiredObject(data, "charge", "data");

        String chargeId = Payloads.requiredText(charge, "id", "data.charge");
        long amountMinor = Payloads.requiredInteger(charge, "amount", "data.charge",
                1, Payloads.MAXIMUM_AMOUNT_MINOR);
        String currency = Payloads.requiredText(charge, "currency", "data.charge").toUpperCase(Locale.ROOT);
        if (!CURRENCY.equals(currency)) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`data.charge.currency` is not the provider's currency");
        }
        String policyNumber = Payloads.policyNumber(
                Payloads.requiredText(charge, "reference", "data.charge"), COUNTRY);

        String status = switch (type) {
            case "charge.succeeded" -> "CAPTURED";
            case "charge.failed" -> "FAILED";
            case "charge.refunded" -> "REFUNDED";
            default -> throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`type` is not a state this provider defines");
        };

        Instant occurredAt = Payloads.instantOfEpochSecond(created, "created");
        Instant statusChangedAt = switch (status) {
            case "CAPTURED" -> stateTime(charge, "paid_at", created);
            case "REFUNDED" -> stateTime(charge, "refunded_at", created);
            default -> occurredAt;
        };

        Long refundedAmountMinor = null;
        String refundedAmountSemantics = null;
        String refundScope = null;
        if ("REFUNDED".equals(status)) {
            refundedAmountMinor = Payloads.optionalInteger(charge, "amount_refunded", "data.charge",
                    1, Payloads.MAXIMUM_AMOUNT_MINOR);
            if (refundedAmountMinor == null) {
                refundScope = "UNKNOWN";
            } else {
                refundedAmountSemantics = "PROVIDER_REPORTED_UNSPECIFIED";
                refundScope = refundedAmountMinor == amountMinor
                        ? "FULL"
                        : refundedAmountMinor < amountMinor ? "PARTIAL" : "UNKNOWN";
            }
        }

        return new CanonicalEvent(
                eventId,
                Provider.QUETZAL,
                COUNTRY,
                "QZ-MX-" + chargeId,
                chargeId,
                policyNumber,
                amountMinor,
                CURRENCY,
                2,
                status,
                refundedAmountMinor,
                refundedAmountSemantics,
                refundScope,
                statusChangedAt,
                occurredAt,
                receivedAt,
                chargeId,
                Payloads.sha256Hex(request.rawBody()));
    }

    /**
     * The state time the design assigns to this transition.
     *
     * <p>{@code paid_at} and {@code refunded_at} are nullable in the provider contract. When the
     * field is absent the event creation time is used, because rejecting an authentic notification
     * would lose a real payment over a missing timestamp that is seconds away.
     */
    private static Instant stateTime(JsonNode charge, String field, long created) {
        Long seconds = Payloads.optionalInteger(charge, field, "data.charge", 1, Long.MAX_VALUE);
        return Payloads.instantOfEpochSecond(seconds == null ? created : seconds, field);
    }
}
