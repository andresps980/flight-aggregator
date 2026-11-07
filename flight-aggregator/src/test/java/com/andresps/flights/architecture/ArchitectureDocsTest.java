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
 * A rename or a move that the diagrams do not follow fails the build instead of leaving them silently stale. Only type
 * names are checked, not members or relationships: the diagrams are a curated view, not generated output.
 *
 * <p>The parser is strict so it cannot miss types silently: every line outside a class body must be a statement it
 * understands (a comment, a declaration, a relation, a note…), otherwise the line is reported.
 *
 * <p>Plain file reading, no Spring context. The docs live outside this module, so the repository root is found by
 * walking up from the working directory; the test also guards the gds-mock pages, so a gds-mock rename fails the full
 * build ({@code ./mvnw verify}, CI), not {@code -pl gds-mock}.
 */
class ArchitectureDocsTest {

    private static final Pattern MERMAID_BLOCK =
            Pattern.compile("(?ms)^[ \\t]*(```|~~~)mermaid[ \\t]*\\n(.*?)^[ \\t]*\\1[ \\t]*$");
    private static final Pattern PACKAGE_LINE = Pattern.compile("^%%\\s*package:\\s*([\\w.]+)$");
    private static final Pattern IGNORED_LINE =
            Pattern.compile("^(?:%%.*|classDiagram(?:-v2)?|direction\\s+\\w+|note\\b.*|style\\b.*|classDef\\b.*|)$");
    private static final String NAME = "(\\w+)(?:~[^~]*~)?";
    private static final Pattern CLASS_DECLARATION = Pattern.compile("^class\\s+" + NAME + ".*$");
    private static final Pattern ANNOTATION = Pattern.compile("^<<[^>]+>>\\s*(\\w+)$");
    private static final Pattern COLON_MEMBER = Pattern.compile("^(\\w+)\\s*:.*$");
    /** Every Mermaid class-diagram link: heads {@code <|, <, *, o, ()}, a solid or dotted line, mirrored tails. */
    private static final String ARROW = "(?:<\\|?|\\*|o|\\(\\))?(?:--|\\.\\.)(?:\\|?>|\\*|o|\\(\\))?";

    private static final String CARDINALITY = "(?:\"[^\"]*\"\\s*)?";
    private static final Pattern RELATION =
            Pattern.compile("^" + NAME + "\\s*" + CARDINALITY + ARROW + "\\s*" + CARDINALITY + NAME + "\\s*(?::.*)?$");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @Test
    @DisplayName("every type in the docs/architecture class diagrams exists in the package the diagram names")
    void diagramTypesExistInTheirPackages() {
        Path root = repositoryRoot();
        List<String> problems = new ArrayList<>();
        int diagrams = 0;
        for (Path page : markdownFiles(root.resolve("docs/architecture"))) {
            for (ClassDiagram diagram : classDiagrams(read(page))) {
                diagrams++;
                problems.addAll(check(root, root.relativize(page).toString(), diagram));
            }
        }

        assertThat(diagrams).as("class diagrams found under docs/architecture").isGreaterThan(10);
        assertThat(problems).as("stale architecture diagrams").isEmpty();
    }

    /** The sample uses the real {@code search.domain.Cursor} as the type that exists; rename it here if it moves. */
    @Test
    @DisplayName("the drift guard reports unknown types, unrecognised lines, empty diagrams and missing package lines")
    void reportsWhatItCannotVerify() {
        Path root = repositoryRoot();
        String page = """
                ```mermaid
                classDiagram
                    %% package: com.andresps.flights.search.domain
                    class Cursor {
                        +encode() String
                        A --> NotARelationInsideABody
                    }
                    class RenamedAway~T~
                    <<interface>> Annotated
                    Cursor *-- "1" PagePosition : position
                    Generic~T~ <|--|> Cursor
                    Cursor ()-- Lollipop
                    Colon : +member()
                    Cursor ==> Unparsed
                ```

                - list item:

                  ~~~mermaid
                  classDiagram
                      class Orphan
                  ~~~

                ```mermaid
                classDiagram
                    %% package: com.andresps.flights.search.domain
                    direction LR
                ```

                ```mermaid
                flowchart LR
                    a --> b
                ```
                """;

        List<ClassDiagram> diagrams = classDiagrams(page);
        List<String> problems = new ArrayList<>();
        diagrams.forEach(d -> problems.addAll(check(root, "sample.md", d)));

        assertThat(diagrams).hasSize(3);
        assertThat(diagrams.getFirst().types())
                .containsExactly("Cursor", "RenamedAway", "Annotated", "PagePosition", "Generic", "Lollipop", "Colon");
        assertThat(problems)
                .containsExactly(
                        "sample.md: unrecognised line 'Cursor ==> Unparsed'",
                        "sample.md: RenamedAway is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: Annotated is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: Generic is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: Lollipop is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: Colon is not declared in [com.andresps.flights.search.domain]",
                        "sample.md: a class diagram without a '%% package:' line",
                        "sample.md: a class diagram with no types");
    }

    record ClassDiagram(List<String> packages, Set<String> types, List<String> unrecognised) {}

    static List<ClassDiagram> classDiagrams(String markdown) {
        List<ClassDiagram> diagrams = new ArrayList<>();
        Matcher block = MERMAID_BLOCK.matcher(markdown);
        while (block.find()) {
            String body = block.group(2);
            if (!body.strip().startsWith("classDiagram")) {
                continue;
            }
            List<String> packages = new ArrayList<>();
            Set<String> types = new LinkedHashSet<>();
            List<String> unrecognised = new ArrayList<>();
            boolean inClassBody = false;
            for (String raw : body.split("\\R")) {
                String line = raw.strip();
                if (inClassBody) {
                    inClassBody = !line.equals("}");
                    continue;
                }
                Matcher pkg = PACKAGE_LINE.matcher(line);
                Matcher declaration = CLASS_DECLARATION.matcher(line);
                Matcher relation = RELATION.matcher(line);
                Matcher annotation = ANNOTATION.matcher(line);
                Matcher colonMember = COLON_MEMBER.matcher(line);
                if (pkg.matches()) {
                    packages.add(pkg.group(1));
                } else if (IGNORED_LINE.matcher(line).matches()) {
                    continue;
                } else if (declaration.matches()) {
                    types.add(declaration.group(1));
                    inClassBody = line.endsWith("{");
                } else if (relation.matches()) {
                    types.add(relation.group(1));
                    types.add(relation.group(2));
                } else if (annotation.matches()) {
                    types.add(annotation.group(1));
                } else if (colonMember.matches()) {
                    types.add(colonMember.group(1));
                } else {
                    unrecognised.add(line);
                }
            }
            diagrams.add(new ClassDiagram(packages, types, unrecognised));
        }
        return diagrams;
    }

    private static List<String> check(Path root, String where, ClassDiagram diagram) {
        List<String> problems = new ArrayList<>();
        diagram.unrecognised().forEach(line -> problems.add(where + ": unrecognised line '" + line + "'"));
        if (diagram.packages().isEmpty()) {
            problems.add(where + ": a class diagram without a '%% package:' line");
            return problems;
        }
        if (diagram.types().isEmpty()) {
            problems.add(where + ": a class diagram with no types");
        }
        List<String> sources = new ArrayList<>();
        for (String pkg : diagram.packages()) {
            Path dir = sourceDirectory(root, pkg);
            if (!Files.isDirectory(dir)) {
                problems.add(where + ": package " + pkg + " does not exist (" + root.relativize(dir) + ")");
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".java"))
                        .map(f -> COMMENTS.matcher(read(f)).replaceAll(" "))
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
