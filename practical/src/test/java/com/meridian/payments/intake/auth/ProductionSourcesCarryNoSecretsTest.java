package com.meridian.payments.intake.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** NFR6: no secret material in production sources; sandbox fixtures belong to tests only. */
class ProductionSourcesCarryNoSecretsTest {

    @Test
    void mainSourcesContainNoSecretMaterial() throws IOException {
        Path main = Path.of("src", "main");
        assumeTrue(Files.isDirectory(main), "test must run from the practical module");

        List<Path> files;
        try (Stream<Path> walk = Files.walk(main)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(content.contains(Fixtures.QUETZAL_SECRET),
                    () -> file + " contains the sandbox Quetzal secret");
            assertFalse(content.contains(Fixtures.ANDINA_SECRET),
                    () -> file + " contains the sandbox Andina secret");
            assertFalse(content.contains("qz_test_whsec_"),
                    () -> file + " contains a sandbox Quetzal secret prefix");
            assertFalse(content.contains("andina_test_secret_"),
                    () -> file + " contains a sandbox Andina secret prefix");
        }
    }
}
