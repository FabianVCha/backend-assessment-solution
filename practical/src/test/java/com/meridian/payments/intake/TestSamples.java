package com.meridian.payments.intake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads the published provider samples and the published contracts from the repository.
 *
 * <p>Shared by the canonical-mapping tests, which need the raw bytes and the event schema rather than
 * hand-written fixtures: a copy of a sample would let the test and the contract drift apart.
 */
public final class TestSamples {

    public static final String QZ_SUCCEEDED = "quetzal-mx/samples/01-charge-succeeded.json";
    public static final String QZ_FAILED = "quetzal-mx/samples/02-charge-failed.json";
    public static final String QZ_REFUNDED = "quetzal-mx/samples/03-charge-refunded.json";

    public static final String AN_PENDIENTE = "andina-co/samples/01-transaccion-pendiente.json";
    public static final String AN_APROBADA = "andina-co/samples/02-transaccion-aprobada.json";
    public static final String AN_REINTENTO = "andina-co/samples/03-transaccion-aprobada-reintento.json";
    public static final String AN_REVERSADA = "andina-co/samples/04-transaccion-reversada.json";

    private TestSamples() {
    }

    public static byte[] read(String relativeToProviders) {
        try {
            return Files.readAllBytes(providerRoot().resolve(relativeToProviders));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read provider sample " + relativeToProviders, e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the event contract", e);
        }
    }

    /** Repository root, the parent of {@code reference/}. */
    public static Path repoRoot() {
        return providerRoot().getParent().getParent();
    }

    private static Path providerRoot() {
        List<Path> candidates = List.of(
                Path.of("..", "reference", "providers"),
                Path.of("reference", "providers"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
                "reference/providers not found from " + Path.of("").toAbsolutePath());
    }

    public static String asText(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
