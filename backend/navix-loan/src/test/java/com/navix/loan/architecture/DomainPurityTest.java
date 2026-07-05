package com.navix.loan.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Hexagonal guardrail: the loan {@code domain} package must stay framework-agnostic — no Spring and
 * no JPA/persistence imports may leak into the pure business types. Ports live in {@code domain}/
 * {@code application} and are implemented by adapters; the domain itself owns rules only.
 *
 * <p>This is a lightweight, dependency-free stand-in for an ArchUnit rule (ArchUnit is not on the
 * classpath). When ArchUnit is added (see REFACTOR_PLAN.md — guardrails), replace this with the
 * canonical {@code noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()}
 * rule. The domain is clean today, so this pins that state and fails the build on any regression.
 */
class DomainPurityTest {

    /** Resolved relative to the module base dir (Surefire sets user.dir to the module root). */
    private static final Path DOMAIN = Path.of("src/main/java/com/navix/loan/domain");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import org.springframework",
            "import jakarta.persistence",
            "import javax.persistence");

    @Test
    void domainHasNoSpringOrJpaImports() throws IOException {
        assertThat(Files.isDirectory(DOMAIN))
                .as("loan domain package must exist at %s", DOMAIN.toAbsolutePath())
                .isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            for (Path java : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(java)) {
                    String trimmed = line.strip();
                    for (String forbidden : FORBIDDEN_IMPORTS) {
                        if (trimmed.startsWith(forbidden)) {
                            violations.add(DOMAIN.relativize(java) + " -> " + trimmed);
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("com.navix.loan.domain must not depend on Spring or JPA (keep the hexagon pure)")
                .isEmpty();
    }
}
