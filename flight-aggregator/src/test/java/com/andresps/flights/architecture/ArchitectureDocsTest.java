package com.andresps.flights.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the class diagrams in {@code docs/architecture/}: every type a Mermaid {@code classDiagram} draws
 * must be declared in one of the packages the diagram names with a {@code %% package: <name>} line, in either module.
 * A rename or a move that the diagrams do not follow fails the build instead of leaving them silently stale.
 *
 * <p>Plain file reading, no Spring context: the docs live outside this module, so the repository root is found by
 * walking up from the working directory.
 */
class ArchitectureDocsTest {

    private static final Pattern MERMAID_BLOCK = Pattern.compile("(?ms)^```mermaid\\s*\\n(.*?)^```");
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*%%\\s*package:\\s*([\\w.]+)\\s*$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("(?m)^\\s*class\\s+(\\w+)");
    private static final String ARROW =
            "(?:<\\|--|--\\|>|<\\|\\.\\.|\\.\\.\\|>|\\*--|--\\*|o--|--o|<--|-->|<\\.\\.|\\.\\.>|--|\\.\\.)";
    private static final String CARDINALITY = "(?:\"[^\"]*\"\\s*)?";
    private static final Pattern RELATION =
            Pattern.compile("(?m)^\\s*(\\w+)\\s*" + CARDINALITY + ARROW + "\\s*" + CARDINALITY + "(\\w+)\\b");

    @Test
    @DisplayName("every type in the docs/architecture class diagrams exists in the package the diagram names")
    void diagramTypesExistInTheirPackages() {
        Path root = repositoryRoot();
        List<Path> pages = markdownFiles(root.resolve("docs/architecture"));
        List<String> problems = new ArrayList<>();
        int diagrams = 0;
        for (Path page : pages) {
            for (ClassDiagram diagram : classDiagrams(read(page))) {
                diagrams++;
                problems.addAll(check(root, root.relativize(page).toString(), diagram));
            }
        }

        assertThat(diagrams).as("class diagrams found under docs/architecture").isGreaterThan(10);
        assertThat(problems).as("stale architecture diagrams").isEmpty();
    }

    @Test
    @DisplayName("the drift guard reports a type that does not exist and a missing package line")
    void reportsUnknownTypesAndMissingPackages() {
        Path root = repositoryRoot();
        String page = """
                ```mermaid
                classDiagram
                    %% package: com.andresps.flights.search.domain
                    class Cursor {
                        +encode() String
                    }
                    class RenamedAway
                    Cursor *-- "1" PagePosition
                    Cursor ..> GoneToo : throws
                ```

                ```mermaid
                classDiagram
                    class Orphan
                ```

                ```mermaid
                flowchart LR
                    a --> b
                ```
                """;

        List<ClassDiagram> diagrams = classDiagrams(page);
        List<String> problems = new ArrayList<>();
        diagrams.forEach(d -> problems.addAll(check(root, "sample.md", d)));

        assertThat(diagrams).hasSize(2);
        assertThat(diagrams.getFirst().types()).containsExactly("Cursor", "RenamedAway", "PagePosition", "GoneToo");
        assertThat(problems)
                .containsExactly(
                        "sample.md: RenamedAway is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: GoneToo is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: a class diagram without a '%% package:' line");
    }

    record ClassDiagram(List<String> packages, Set<String> types) {}

    static List<ClassDiagram> classDiagrams(String markdown) {
        List<ClassDiagram> diagrams = new ArrayList<>();
        Matcher block = MERMAID_BLOCK.matcher(markdown);
        while (block.find()) {
            String body = block.group(1);
            if (!body.strip().startsWith("classDiagram")) {
                continue;
            }
            List<String> packages =
                    PACKAGE_LINE.matcher(body).results().map(m -> m.group(1)).toList();
            Set<String> types = new LinkedHashSet<>();
            CLASS_DECLARATION.matcher(body).results().forEach(m -> types.add(m.group(1)));
            RELATION.matcher(body).results().forEach(m -> {
                types.add(m.group(1));
                types.add(m.group(2));
            });
            diagrams.add(new ClassDiagram(packages, types));
        }
        return diagrams;
    }

    private static List<String> check(Path root, String where, ClassDiagram diagram) {
        if (diagram.packages().isEmpty()) {
            return List.of(where + ": a class diagram without a '%% package:' line");
        }
        List<String> problems = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (String pkg : diagram.packages()) {
            Path dir = sourceDirectory(root, pkg);
            if (!Files.isDirectory(dir)) {
                problems.add(where + ": package " + pkg + " does not exist (" + root.relativize(dir) + ")");
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".java"))
                        .map(ArchitectureDocsTest::read)
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String type : diagram.types()) {
            Pattern declaration = Pattern.compile("\\b(?:class|interface|record|enum)\\s+" + type + "\\b");
            if (sources.stream().noneMatch(source -> declaration.matcher(source).find())) {
                problems.add(where + ": " + type + " is not declared in " + diagram.packages());
            }
        }
        return problems;
    }

    private static Path sourceDirectory(Path root, String pkg) {
        String module = pkg.startsWith("com.andresps.gdsmock") ? "gds-mock" : "flight-aggregator";
        return root.resolve(module).resolve("src/main/java").resolve(pkg.replace('.', '/'));
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("docs/architecture"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with docs/architecture").isNotNull();
        return dir;
    }

    private static List<Path> markdownFiles(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
