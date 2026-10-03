package com.ticketrush.security;

import static com.ticketrush.security.TestJwts.bearer;
import static com.ticketrush.security.TestJwts.loadTestToken;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(properties = {
        "ticketrush.security.public-paths=GET /api/open",
        "ticketrush.security.load-test.secret=" + TestJwts.LOAD_TEST_SECRET})
@AutoConfigureMockMvc
class ResourceServerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void publicPathsAndHealthNeedNoToken() {
        assertThat(mvc.get().uri("/api/open")).hasStatusOk().hasBodyTextEqualTo("open");
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    @Test
    void missingTokenIsA401ProblemWithBearerChallenge() {
        MvcTestResult result = mvc.get().uri("/api/me").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void wrongRoleIsA403Problem() {
        assertThat(mvc.post().uri("/api/publish").with(TestJwts.customer("an")))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.instance").isEqualTo("/api/publish");
        assertThat(mvc.post().uri("/api/publish").with(TestJwts.organizer("olivia")))
                .hasStatusOk().hasBodyTextEqualTo("published by olivia");
        assertThat(mvc.post().uri("/api/publish").with(TestJwts.admin("ada"))).hasStatusOk();
    }

    /** The role is checked before the body is bound, so an invalid body tells a customer nothing. */
    @Test
    void roleIsCheckedBeforeTheBodyIsValidated() {
        assertThat(mvc.post().uri("/api/drafts").with(TestJwts.customer("an"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/api/drafts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/drafts").with(TestJwts.organizer("olivia"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void callerComesFromTheVerifiedToken() {
        String token = loadTestToken(TestJwts.LOAD_TEST_SECRET, "fan-42", Roles.CUSTOMER);
        assertThat(mvc.post().uri("/api/buy").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .hasStatusOk().hasBodyTextEqualTo("bought for fan-42");
        assertThat(mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.email", email -> email.assertThat().isEqualTo("fan-42@example.com"))
                .hasPathSatisfying("$.roles", roles -> roles.assertThat().asList().containsExactly("CUSTOMER"));
    }

    /** NFR-MAINT-02: the API description is public, names the Keycloak login and hides the Caller parameter. */
    @Test
    void publishesAnOpenApiDescriptionWithTheKeycloakLogin() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl",
                        url -> url.assertThat().isEqualTo("http://localhost:8180/realms/ticketrush/protocol/openid-connect/token"))
                .hasPathSatisfying("$.paths['/api/buy'].post", buy -> buy.assertThat().asMap().doesNotContainKey("parameters"));
    }

    @Test
    void forgedOrForeignTokensAreRejected() {
        String wrongKey = loadTestToken("test-wrong-secret-that-is-long-enough-0000", "mallory", Roles.ADMIN);
        assertThat(mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(wrongKey)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        // Nothing to do with the old X-User-Id header any more.
        assertThat(mvc.get().uri("/api/me").header("X-User-Id", "alice")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
