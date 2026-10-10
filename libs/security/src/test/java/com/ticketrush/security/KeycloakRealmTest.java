package com.ticketrush.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Checks the realm file in infra/keycloak against a real Keycloak: the accounts, the default
 * CUSTOMER role, the flat roles claim and the ticketrush-api audience the services rely on.
 */
@Testcontainers
class KeycloakRealmTest {

    private static final String PASSWORD = "realm-test-password";

    @Container
    static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.5")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("DEMO_USER_PASSWORD", PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/keycloak/ticketrush-realm.json"),
                    "/opt/keycloak/data/import/ticketrush-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000));

    static String realm;
    static JwtDecoder decoder;

    @BeforeAll
    static void decoder() {
        realm = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ticketrush";
        decoder = new ResourceServerConfig().jwtDecoder(new SecurityProperties(realm,
                URI.create(realm + "/protocol/openid-connect/certs"), "ticketrush-api", List.of(),
                new SecurityProperties.LoadTest(null)));
    }

    @Test
    void customersGetTheCustomerRoleByDefault() {
        Jwt jwt = decoder.decode(token("alice@ticketrush.dev"));

        assertThat(jwt.getIssuer()).hasToString(realm);
        assertThat(jwt.getAudience()).contains("ticketrush-api");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@ticketrush.dev");
        assertThat(roles(jwt)).contains("ROLE_CUSTOMER").doesNotContain("ROLE_ORGANIZER", "ROLE_ADMIN");
    }

    @Test
    void organizerAndAdminCarryTheirRoles() {
        assertThat(roles(decoder.decode(token("organizer@ticketrush.dev")))).contains("ROLE_ORGANIZER", "ROLE_CUSTOMER");
        assertThat(roles(decoder.decode(token("admin@ticketrush.dev")))).contains("ROLE_ADMIN");
    }

    @Test
    void loadTestTokensAreRejectedUnlessThatIssuerIsSwitchedOn() {
        String token = TestJwts.loadTestToken(TestJwts.LOAD_TEST_SECRET, "fan", Roles.CUSTOMER);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    private static List<String> roles(Jwt jwt) {
        return ResourceServerConfig.authenticationConverter().convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    @SuppressWarnings("unchecked")
    private static String token(String username) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "ticketrush-cli");
        form.add("username", username);
        form.add("password", PASSWORD);
        Map<String, Object> response = RestClient.create().post()
                .uri(realm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) response.get("access_token");
    }
}
