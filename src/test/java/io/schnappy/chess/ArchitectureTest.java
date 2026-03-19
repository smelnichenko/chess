package io.schnappy.chess;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

// Chess has a flatter structure: most classes live directly in io.schnappy.chess
// with only config, security, and kafka as subpackages.
@AnalyzeClasses(packages = "io.schnappy.chess", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    // -------------------------------------------------------------------------
    // Security isolation: security package must not depend on business logic
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule security_must_not_depend_on_kafka =
            noClasses()
                    .that().resideInAPackage("..security..")
                    .should().dependOnClassesThat().resideInAPackage("..kafka..")
                    .as("Security classes must not depend on kafka classes");

    @ArchTest
    static final ArchRule security_must_not_depend_on_config =
            noClasses()
                    .that().resideInAPackage("..security..")
                    .should().dependOnClassesThat().resideInAPackage("..config..")
                    .as("Security classes must not depend on config classes");

    // -------------------------------------------------------------------------
    // GatewayAuthFilter must reside in the security package
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule gateway_auth_filter_in_security_package =
            classes()
                    .that().haveSimpleName("GatewayAuthFilter")
                    .should().resideInAPackage("..security..")
                    .as("GatewayAuthFilter must reside in the security package");

    // -------------------------------------------------------------------------
    // Kafka must not depend on config (kafka is infrastructure, config wires it)
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule kafka_must_not_depend_on_config =
            noClasses()
                    .that().resideInAPackage("..kafka..")
                    .should().dependOnClassesThat().resideInAPackage("..config..")
                    .as("Kafka classes must not depend on config classes");

    // -------------------------------------------------------------------------
    // No package cycles across named subpackages
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_cycles_in_chess_packages =
            slices()
                    .matching("io.schnappy.chess.(*)..")
                    .should().beFreeOfCycles()
                    .as("Packages under io.schnappy.chess must be free of cycles");
}
