package com.andresps.gdsmock.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executable architecture rules for gds-mock (see ADR-0002, ADR-0012, ADR-0013).
 *
 * <p>Layout per feature ({@code inventory}, {@code chaos}): {@code domain} (plain Java) and {@code infrastructure}.
 * There is no {@code application} layer: the mock has no use-case orchestration or driven ports, so ports would be
 * single-implementation ceremony. The module is a test double of external systems and must never depend on the
 * aggregator.
 */
@AnalyzeClasses(packages = "com.andresps.gdsmock", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule never_depends_on_the_aggregator = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.andresps.flights..")
            .because("gds-mock simulates external providers and shares nothing with flight-aggregator");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.fasterxml..",
                    "tools.jackson..",
                    "javax.xml..",
                    "org.w3c..")
            .because("the generator and chaos policy are plain Java (ADR-0013)");

    @ArchTest
    static final ArchRule serializers_are_provider_formats = classes()
            .that()
            .resideInAPackage("..inventory.infrastructure.adapter.in.web.format..")
            .and()
            .haveSimpleNameEndingWith("Format")
            .should()
            .implement(ProviderFormat.class)
            .because("a new provider is a new ProviderFormat class");

    @ArchTest
    static final ArchRule serializers_know_nothing_about_chaos = noClasses()
            .that()
            .resideInAPackage("..inventory..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..chaos..")
            .because("chaos is applied around the feeds, never inside serializers or controllers");

    @ArchTest
    static final ArchRule controllers_live_in_inbound_adapters = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAPackage("..infrastructure.adapter.in..");

    @ArchTest
    static final ArchRule features_are_free_of_cycles =
            slices().matching("com.andresps.gdsmock.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
