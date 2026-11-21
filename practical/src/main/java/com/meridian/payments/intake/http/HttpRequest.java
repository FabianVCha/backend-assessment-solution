package com.meridian.payments.intake.http;

import java.util.Map;
import java.util.Objects;

/**
 * A webhook request as the transport hands it over: path, headers and the exact body bytes.
 *
 * <p>No method field: these routes only exist as {@code POST}, and the transport rejects anything else
 * before it reaches the handler. The body stays as bytes because the signature covers them.
 */
public record HttpRequest(String path, Map<String, String> headers, byte[] body) {

    public HttpRequest {
        Objects.requireNonNull(path, "path");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
