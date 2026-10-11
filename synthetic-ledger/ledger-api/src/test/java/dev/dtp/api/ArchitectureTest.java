package dev.dtp.api;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void domainAndApplicationHaveNoFrameworkOrAdapterDependencies() {
        var classes = new ClassFileImporter().importPackages("dev.dtp");
        noClasses().that().resideInAnyPackage("dev.dtp.domain..", "dev.dtp.application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "dev.dtp.adapters..", "dev.dtp.api..")
                .check(classes);
    }
    @Test void domainDoesNotDependOnApplication() {
        noClasses().that().resideInAPackage("dev.dtp.domain..")
                .should().dependOnClassesThat().resideInAPackage("dev.dtp.application..")
                .check(new ClassFileImporter().importPackages("dev.dtp"));
    }
}
