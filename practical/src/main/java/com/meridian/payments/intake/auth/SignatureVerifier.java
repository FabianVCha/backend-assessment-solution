package com.meridian.payments.intake.auth;

/** Verifies the authenticity of a webhook request before any parsing happens. */
public interface SignatureVerifier {

    SignatureVerification verify(WebhookRequest request);
}
