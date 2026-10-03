package com.ticketrush.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

import com.ticketrush.testing.TicketRushArchitecture;

/** The package layout shared by every service (docs/adr/0008). */
@AnalyzeClasses(packages = "com.ticketrush.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests rules = ArchTests.in(TicketRushArchitecture.class);
}
