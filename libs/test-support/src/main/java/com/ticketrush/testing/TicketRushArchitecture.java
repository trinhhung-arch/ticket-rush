package com.ticketrush.testing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The package layout every service follows (docs/adr/0008). A service runs these with
 * {@code @ArchTest ArchTests rules = ArchTests.in(TicketRushArchitecture.class)}.
 *
 * <p>Patterns read {@code com.ticketrush.*.domain..}: one segment for the service, so the shared
 * libraries ({@code com.ticketrush.web}, {@code com.ticketrush.messaging}) never count as a layer.
 * A service without some layer passes the rules about it.
 */
public final class TicketRushArchitecture {

    private static final String WEB = "com.ticketrush.*.web..";
    private static final String MESSAGING = "com.ticketrush.*.messaging..";
    private static final String DOMAIN = "com.ticketrush.*.domain..";

    private TicketRushArchitecture() {
    }

    /** Dependencies point inward: the domain knows nothing of how it is called or what it calls. */
    @ArchTest
    public static final ArchRule domainDependsOnNoAdapter = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    WEB, MESSAGING, "com.ticketrush.*.saga..", "com.ticketrush.*.client..", "com.ticketrush.*.psp..")
            .allowEmptyShould(true);

    /** HTTP and Kafka are two separate ways in. */
    @ArchTest
    public static final ArchRule webDoesNotCallMessaging = noClasses()
            .that().resideInAPackage(WEB)
            .should().dependOnClassesThat().resideInAPackage(MESSAGING)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule messagingDoesNotCallWeb = noClasses()
            .that().resideInAPackage(MESSAGING)
            .should().dependOnClassesThat().resideInAPackage(WEB)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule packagesAreFreeOfCycles = slices()
            .matching("com.ticketrush.(*).(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule controllersLiveInWeb = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().resideInAPackage(WEB)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule kafkaListenersLiveInMessaging = methods()
            .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
            .should().beDeclaredInClassesThat().resideInAPackage(MESSAGING)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule entitiesLiveInDomain = classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAPackage(DOMAIN)
            .allowEmptyShould(true);

    /** At the root of the service's package, so component scanning covers all of the service. */
    @ArchTest
    public static final ArchRule applicationAtServiceRoot = classes()
            .that().areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should().resideInAPackage("com.ticketrush.*");
}
