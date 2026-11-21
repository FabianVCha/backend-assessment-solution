package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.WebhookRequest;

import java.time.Instant;
import java.util.Objects;

/**
 * Andina Pay (CO) 2.3 notification to canonical event.
 *
 * <p>Identity is derived, because {@code notificacion_id} changes on every attempt. The provider
 * states that {@code transaccion.id} plus {@code transaccion.estado} identifies the business event
 * and does not change between attempts, so that pair is the key. Amounts arrive as a decimal string
 * in major units and become an integer in minor units without passing through a float.
 */
public final class AndinaCanonicalEventMapper implements CanonicalEventMapper {

    private static final String COUNTRY = "CO";
    private static final String CURRENCY = "COP";

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public CanonicalEvent map(WebhookRequest request, Instant receivedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (request.provider() != Provider.ANDINA) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "the request does not belong to Andina");
        }

        ObjectNode payload = Payloads.parse(request.rawBody());
        // Required by the contract, never the event key: it changes on every retry.
        Payloads.requiredText(payload, "notificacion_id", "$");
        String notificationTime = Payloads.requiredText(payload, "fecha_notificacion", "$");
        JsonNode transaction = Payloads.requiredObject(payload, "transaccion", "$");

        String transactionId = Payloads.requiredText(transaction, "id", "transaccion");
        String state = Payloads.requiredText(transaction, "estado", "transaccion");
        String value = Payloads.requiredText(transaction, "valor", "transaccion");
        String currency = Payloads.requiredText(transaction, "moneda", "transaccion");
        if (!CURRENCY.equals(currency)) {
            throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`transaccion.moneda` is not the provider's currency");
        }
        String policyNumber = Payloads.policyNumber(
                Payloads.requiredText(transaction, "referencia", "transaccion"), COUNTRY);
        String stateTime = Payloads.requiredText(transaction, "fecha_transaccion", "transaccion");

        long amountMinor = Payloads.centsFromMajorUnits(value, "transaccion.valor");
        String status = switch (state) {
            case "PENDIENTE" -> "PENDING";
            case "APROBADA" -> "CAPTURED";
            case "RECHAZADA" -> "FAILED";
            case "REVERSADA" -> "REFUNDED";
            default -> throw Payloads.failure(CanonicalMappingException.Failure.SCHEMA_VALIDATION_FAILED,
                    "`transaccion.estado` is not a state this provider defines");
        };

        return new CanonicalEvent(
                Payloads.sha256Hex(transactionId + "|" + state),
                Provider.ANDINA,
                COUNTRY,
                "AN-CO-" + transactionId,
                transactionId,
                policyNumber,
                amountMinor,
                CURRENCY,
                2,
                status,
                null,
                null,
                "REFUNDED".equals(status) ? "UNKNOWN" : null,
                Payloads.instantWithOffset(stateTime, "transaccion.fecha_transaccion"),
                Payloads.instantWithOffset(notificationTime, "fecha_notificacion"),
                receivedAt,
                transactionId,
                Payloads.sha256Hex(request.rawBody()));
    }
}
