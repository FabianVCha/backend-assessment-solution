package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;

import java.time.Instant;
import java.util.Objects;

/**
 * Austral Pagos (CL) notification to canonical event.
 *
 * <p>Three things this provider does differently from the other two, and each one is a decision:
 *
 * <ul>
 *   <li>It signs no timestamp, so the event id is the replay key. The provider promises the same
 *       {@code eventId} on every retry, which is what makes the inbox's unique constraint the right
 *       place to answer a repeat.
 *   <li>CLP has no minor unit. The amount arrives as an integer string and becomes
 *       {@code amount_minor} unchanged, with {@code currency_exponent} 0. Scaling it by a hundred
 *       would report a payment of 45,990 as 4,599,000.
 *   <li>A reversal carries no amount, so {@code refund_scope} is {@code UNKNOWN} and the three refund
 *       fields stay null: the provider said nothing about the extent, and inferring "full" from "the
 *       payment was reversed" is a fact nobody stated.
 * </ul>
 *
 * <p>The payer block is ignored. The canonical event carries no name, RUT or email, and the mapping
 * never reads those fields.
 */
public final class AustralCanonicalEventMapper implements CanonicalEventMapper {

    private static final String COUNTRY = "CL";
    private static final String CURRENCY = "CLP";
    /** CLP has no minor unit: the reported integer is already the amount in the smallest unit. */
    private static final int EXPONENT = 0;

    @Override
    public Provider provider() {
        return Provider.AUSTRAL;
    }

    @Override
    public CanonicalEvent map(WebhookRequest request, Instant receivedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (request.provider() != Provider.AUSTRAL) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "the request does not belong to Austral");
        }

        ObjectNode payload = Payloads.parse(request.rawBody());
        String eventId = Payloads.requiredText(payload, "eventId", "$");
        String eventType = Payloads.requiredText(payload, "eventType", "$");
        String sentAt = Payloads.requiredText(payload, "sentAt", "$");
        JsonNode payment = Payloads.requiredObject(payload, "payment", "$");

        String paymentId = Payloads.requiredText(payment, "paymentId", "payment");
        String amount = Payloads.requiredText(payment, "amount", "payment");
        String currency = Payloads.requiredText(payment, "currency", "payment");
        if (!CURRENCY.equals(currency)) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`payment.currency` is not the provider's currency");
        }
        String policyNumber = Payloads.policyNumber(
                Payloads.requiredText(payment, "policyNumber", "payment"), COUNTRY);
        String occurredAt = Payloads.requiredText(payment, "occurredAt", "payment");

        long amountMinor = Payloads.integerAmountInMajorUnits(amount, "payment.amount");
        String status = switch (eventType) {
            case "PAYMENT_CONFIRMED" -> "CAPTURED";
            case "PAYMENT_REJECTED" -> "FAILED";
            case "PAYMENT_REVERSED" -> "REFUNDED";
            default -> throw Payloads.failure(
                    CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`eventType` is not a type this provider defines");
        };

        return new CanonicalEvent(
                eventId,
                Provider.AUSTRAL,
                COUNTRY,
                // The published contract fixes the payment key shape per country, and the prefix is what
                // lets an operator tell a Quetzal payment from a Chilean one without a join.
                "AU-CL-" + paymentId,
                paymentId,
                policyNumber,
                amountMinor,
                CURRENCY,
                EXPONENT,
                status,
                null,
                null,
                "REFUNDED".equals(status) ? "UNKNOWN" : null,
                Payloads.instantWithOffset(occurredAt, "payment.occurredAt"),
                Payloads.instantWithOffset(sentAt, "sentAt"),
                receivedAt,
                eventId,
                Payloads.sha256Hex(request.rawBody()));
    }
}
