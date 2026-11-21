package com.meridian.payments.intake.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Official sandbox vectors and raw sample bodies from {@code reference/providers/}.
 *
 * <p>Public so the intake tests drive the real verifiers with the published vectors instead of
 * keeping a second copy of the same secrets and signatures.
 */
public final class Fixtures {

    // Sandbox test vectors published by the providers. Test-only fixtures, never production secrets.
    public static final String QUETZAL_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_SECRET = "andina_test_secret_7c3e19ab";
    public static final String AUSTRAL_SECRET = "aus_test_whsec_2b9d6f10";

    public static final SecretProvider SECRETS = new InMemorySecretProvider(Map.of(
            Provider.QUETZAL, QUETZAL_SECRET.getBytes(StandardCharsets.UTF_8),
            Provider.ANDINA, ANDINA_SECRET.getBytes(StandardCharsets.UTF_8),
            Provider.AUSTRAL, AUSTRAL_SECRET.getBytes(StandardCharsets.UTF_8)));

    public static final String QZ_SUCCEEDED = "quetzal-mx/samples/01-charge-succeeded.json";
    public static final String QZ_FAILED = "quetzal-mx/samples/02-charge-failed.json";
    public static final String QZ_REFUNDED = "quetzal-mx/samples/03-charge-refunded.json";

    public static final String AN_PENDIENTE = "andina-co/samples/01-transaccion-pendiente.json";
    public static final String AN_APROBADA = "andina-co/samples/02-transaccion-aprobada.json";
    public static final String AN_REINTENTO = "andina-co/samples/03-transaccion-aprobada-reintento.json";
    public static final String AN_REVERSADA = "andina-co/samples/04-transaccion-reversada.json";

    public record QuetzalVector(String sample, String timestamp, String signature) {
    }

    public record AndinaVector(String sample, String timestamp, String signature) {
    }

    public record AustralVector(String sample, String signature) {
    }

    public static final String AU_CONFIRMED = "austral-cl/samples/01-payment-confirmed.json";
    public static final String AU_REVERSED = "austral-cl/samples/02-payment-reversed.json";

    public static final List<AustralVector> AUSTRAL_VECTORS = List.of(
            new AustralVector(AU_CONFIRMED,
                    "ec52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e1482357"),
            new AustralVector(AU_REVERSED,
                    "e104a041f3f5fd9bd2bcf2dc746f919fd6f759caeedc450e5f1f247e009d9721"));

    public static final List<QuetzalVector> QUETZAL_VECTORS = List.of(
            new QuetzalVector(QZ_SUCCEEDED, "1795888803",
                    "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031"),
            new QuetzalVector(QZ_FAILED, "1795889403",
                    "d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9"),
            new QuetzalVector(QZ_REFUNDED, "1795975203",
                    "a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6"));

    public static final List<AndinaVector> ANDINA_VECTORS = List.of(
            new AndinaVector(AN_PENDIENTE, "2026-11-28T14:02:59-05:00",
                    "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k="),
            new AndinaVector(AN_APROBADA, "2026-11-28T14:03:11-05:00",
                    "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk="),
            new AndinaVector(AN_REINTENTO, "2026-11-28T14:08:11-05:00",
                    "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424="),
            new AndinaVector(AN_REVERSADA, "2026-12-03T09:15:42-05:00",
                    "AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI="));

    private Fixtures() {
    }

    public static byte[] sample(String relative) {
        try {
            return Files.readAllBytes(providerRoot().resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read provider sample " + relative, e);
        }
    }

    public static String quetzalHeader(String timestamp, String... signatures) {
        StringBuilder header = new StringBuilder("t=").append(timestamp);
        for (String signature : signatures) {
            header.append(",v1=").append(signature);
        }
        return header.toString();
    }

    public static WebhookRequest quetzal(String signatureHeader, byte[] body) {
        Map<String, String> headers = new HashMap<>();
        if (signatureHeader != null) {
            headers.put("Quetzal-Signature", signatureHeader);
        }
        return new WebhookRequest(Provider.QUETZAL, headers, body);
    }

    public static WebhookRequest andina(String timestamp, String signature, byte[] body) {
        Map<String, String> headers = new HashMap<>();
        if (timestamp != null) {
            headers.put("X-Andina-Timestamp", timestamp);
        }
        if (signature != null) {
            headers.put("X-Andina-Signature", signature);
        }
        return new WebhookRequest(Provider.ANDINA, headers, body);
    }

    public static WebhookRequest austral(String signature, byte[] body) {
        Map<String, String> headers = new HashMap<>();
        if (signature != null) {
            headers.put("X-Austral-Signature", signature);
        }
        return new WebhookRequest(Provider.AUSTRAL, headers, body);
    }

    private static Path providerRoot() {
        List<Path> candidates = List.of(
                Path.of("..", "reference", "providers"),
                Path.of("reference", "providers"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "reference/providers not found from " + Path.of("").toAbsolutePath());
    }
}
