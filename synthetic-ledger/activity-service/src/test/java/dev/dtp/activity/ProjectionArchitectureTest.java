package dev.dtp.activity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ProjectionArchitectureTest {
    @Test void activityAndContractsCannotDependOnFinancialInternalsOrJpa() {
        noClasses().that().resideInAnyPackage("dev.dtp.activity..", "dev.dtp.contracts..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.dtp.domain..", "dev.dtp.application..", "dev.dtp.adapters..", "dev.dtp.api..", "jakarta.persistence..")
                .check(new ClassFileImporter().importPackages("dev.dtp"));
        assertThatThrownBy(() -> Class.forName("dev.dtp.adapters.persistence.PostgresLedger")).isInstanceOf(ClassNotFoundException.class);
    }
}
