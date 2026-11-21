package com.meridian.payments.intake.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A webhook response: status, headers and an optional JSON body. */
public record HttpResponse(int status, Map<String, String> headers, String body) {

    public HttpResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("not an HTTP status: " + status);
        }
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    public static HttpResponse empty(int status) {
        return new HttpResponse(status, Map.of(), "");
    }

    public static HttpResponse json(int status, String body) {
        return new HttpResponse(status, Map.of("Content-Type", "application/json"), body);
    }

    public HttpResponse withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new HttpResponse(status, copy, body);
    }
}
