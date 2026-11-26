package io.github.federiconbaez.banking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.federiconbaez.banking",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainDependsOnlyOnJdkAndNullnessAnnotations =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "java..", "org.jspecify.annotations..");

  @ArchTest
  static final ArchRule domainDoesNotKnowApplicationOrAdapters =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..application..", "..adapter..");

  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter..");

  @ArchTest
  static final ArchRule noFrameworksAnywhere =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "jakarta..", "javax.persistence..", "lombok..");

  @ArchTest
  static final ArchRule domainUsesNoFloatingPointFields =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.math.BigDecimal");

  @ArchTest
  static final ArchRule jdbcOnlyInsideTheSqliteAdapter =
      noClasses()
          .that()
          .resideOutsideOfPackage("..adapter.sqlite..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.sql..", "org.sqlite..");

  @ArchTest
  static final ArchRule sqliteAdapterDoesNotDependOnOtherAdapters =
      noClasses()
          .that()
          .resideInAPackage("..adapter.sqlite..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.inmemory..");
}
