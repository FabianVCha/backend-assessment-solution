package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.canonical.CanonicalMappingException.Failure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field-level reading rules shared by the two mappers.
 *
 * <p>Every rejection becomes a {@link CanonicalMappingException} whose message names the field and
 * never its value: a provider identifier, a policy number or an amount are all things the operations
 * and privacy rules keep out of logs.
 */
final class Payloads {

    /** Upper bound of {@code amount_minor} in the published event contract. */
    static final long MAXIMUM_AMOUNT_MINOR = 10_000_000_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MAJOR_UNITS = Pattern.compile("^([0-9]{1,13})[.]([0-9]{2})$");
    private static final Pattern INTEGER_UNITS = Pattern.compile("^[0-9]{1,13}$");

    private Payloads() {
    }

    static ObjectNode parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw failure(Failure.MALFORMED_JSON, "body is not a JSON document");
        }
        if (root == null) {
            throw failure(Failure.MALFORMED_JSON, "body is empty");
        }
        if (!root.isObject()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "the notification is not a JSON object");
        }
        return (ObjectNode) root;
    }

    static JsonNode requiredObject(JsonNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, at(path, field) + " is missing or not an object");
        }
        return node;
    }

    static String requiredText(JsonNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, at(path, field) + " is missing or blank");
        }
        return node.asText();
    }

    static long requiredInteger(JsonNode parent, String field, String path, long minimum, long maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, at(path, field) + " is missing or not an integer");
        }
        return withinRange(node.asLong(), minimum, maximum, at(path, field));
    }

    static Long optionalInteger(JsonNode parent, String field, String path, long minimum, long maximum) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, at(path, field) + " is not an integer");
        }
        return withinRange(node.asLong(), minimum, maximum, at(path, field));
    }

    static Instant instantOfEpochSecond(long seconds, String field) {
        try {
            return Instant.ofEpochSecond(seconds);
        } catch (DateTimeException e) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "`" + field + "` is not a usable instant");
        }
    }

    static Instant instantWithOffset(String value, String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "`" + field + "` is not an ISO-8601 instant with offset");
        }
    }

    /**
     * Reads an amount that arrives as a decimal string in major units and returns minor units.
     *
     * <p>String arithmetic on purpose: a factor-of-100 error in a payment amount is not a rounding
     * bug, and the contract fixes the input to exactly two decimals.
     */
    static long centsFromMajorUnits(String value, String field) {
        Matcher matcher = MAJOR_UNITS.matcher(value);
        if (!matcher.matches()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED,
                    "`" + field + "` is not a decimal amount with exactly two decimals");
        }
        long cents;
        try {
            cents = Long.parseLong(matcher.group(1) + matcher.group(2));
        } catch (NumberFormatException e) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "`" + field + "` is outside the contract range");
        }
        return withinRange(cents, 1, MAXIMUM_AMOUNT_MINOR, "`" + field + "`");
    }

    static String policyNumber(String reference, String country) {
        Pattern pattern = Pattern.compile("^POL-" + country + "-[0-9]{9}$");
        if (!pattern.matcher(reference).matches()) {
            throw failure(Failure.POLICY_REFERENCE_UNPARSEABLE,
                    "`reference` is not a " + country + " policy number");
        }
        return reference.toUpperCase(Locale.ROOT);
    }

    /**
     * Reads an amount that arrives as an integer string in a currency with no minor unit.
     *
     * <p>CLP has exponent 0: "45990" is CLP 45,990, and {@code amount_minor} is 45990 with
     * {@code currency_exponent} 0. Multiplying by a hundred here would turn a 45,990 payment into a
     * 4,599,000 one, which is the kind of error a payment system does not get to make twice.
     */
    static long integerAmountInMajorUnits(String value, String field) {
        if (!INTEGER_UNITS.matcher(value).matches()) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "`" + field + "` is not an integer amount");
        }
        try {
            return withinRange(Long.parseLong(value), 1, MAXIMUM_AMOUNT_MINOR, "`" + field + "`");
        } catch (NumberFormatException e) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, "`" + field + "` is outside the contract range");
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the event contract", e);
        }
    }

    static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    static CanonicalMappingException failure(Failure failure, String message) {
        return new CanonicalMappingException(failure, message);
    }

    private static long withinRange(long value, long minimum, long maximum, String field) {
        if (value < minimum || value > maximum) {
            throw failure(Failure.SCHEMA_VALIDATION_FAILED, field + " is outside the contract range");
        }
        return value;
    }

    private static String at(String path, String field) {
        return "`" + ("$".equals(path) ? field : path + "." + field) + "`";
    }
}
