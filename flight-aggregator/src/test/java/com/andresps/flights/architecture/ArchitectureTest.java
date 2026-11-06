package com.andresps.flights.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executable architecture rules (see docs/adr/0002-hexagonal-architecture.md).
 *
 * <p>Layout per feature: {@code <feature>.domain}, {@code <feature>.application},
 * {@code <feature>.infrastructure}. Dependencies point inwards only.
 */
@AnalyzeClasses(packages = "com.andresps.flights", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The OpenAPI customizer documents every property of a {@code *Response} / report {@code *Document} as required,
     * because Jackson writes every record component (spec 006). Omitting nulls would make that description wrong.
     */
    @ArchTest
    static final ArchRule response_records_always_write_every_property = noClasses()
            .that()
            .haveSimpleNameEndingWith("Response")
            .or()
            .haveSimpleNameEndingWith("Document")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.fasterxml.jackson.annotation.JsonInclude")
            .because("OpenApiConfiguration marks their properties required (spec 006)");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..", "tools.jackson..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule application_is_framework_free = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because("use cases are wired in infrastructure.config; see ADR-0002");

    @ArchTest
    static final ArchRule io_and_resilience_stay_in_infrastructure = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.resilience4j..", "javax.xml..", "java.net..", "tools.jackson..", "com.fasterxml..")
            .because("parsing, HTTP and resilience are adapter concerns (ADR-0002, ADR-0014)");

    @ArchTest
    static final ArchRule postgresql_stays_in_the_persistence_adapter = noClasses()
            .that()
            .resideOutsideOfPackage("..infrastructure.adapter.out.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.postgresql..", "org.flywaydb..")
            .because("PostgreSQL-specific code (COPY, advisory locks) lives in the persistence adapter (ADR-0018)");

    @ArchTest
    static final ArchRule redis_stays_in_the_cache_adapter = noClasses()
            .that()
            .resideOutsideOfPackages("..infrastructure.adapter.out.cache..", "..infrastructure.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..", "redis..")
            .because("the search cache is a decorator of the query ports; Redis code lives in its adapter (ADR-0022)");

    @ArchTest
    static final ArchRule cache_adapter_does_not_query_the_database = noClasses()
            .that()
            .resideInAPackage("..infrastructure.adapter.out.cache..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "..adapter.out.persistence..")
            .because("the cache decorates ports; it reaches PostgreSQL only through them (ADR-0022)");

    @ArchTest
    static final ArchRule jdbc_stays_in_persistence_and_wiring = noClasses()
            .that()
            .resideOutsideOfPackages("..infrastructure.adapter.out.persistence..", "..infrastructure.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.springframework.transaction..")
            .because("only the persistence adapter talks SQL; the composition root only hands it a DataSource");

    @ArchTest
    static final ArchRule provider_adapters_do_not_use_the_use_case = noClasses()
            .that()
            .resideInAPackage("..infrastructure.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.service..", "..application.port.in..")
            .because("driven adapters implement outbound ports only");

    @ArchTest
    static final ArchRule controllers_live_in_inbound_adapters = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAPackage("..infrastructure.adapter.in..");

    @ArchTest
    static final ArchRule features_are_free_of_cycles =
            slices().matching("com.andresps.flights.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
