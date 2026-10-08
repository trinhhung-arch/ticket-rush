package com.ticketrush.security;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * NFR-MAINT-02: each service describes its REST API at /v3/api-docs. The gateway's Swagger UI shows
 * them all and logs in through Keycloak (authorization code + PKCE); "Try it out" goes through the
 * gateway, since the server URL is relative to the page.
 */
@AutoConfiguration
class OpenApiConfig {

    static final String SCHEME = "keycloak";

    static {
        // Caller is resolved from the token, not sent by the client.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Caller.class);
    }

    @Bean
    OpenAPI ticketRushOpenApi(@Value("${spring.application.name:ticketrush}") String service, SecurityProperties security) {
        String oidc = security.issuer() + "/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info().title("TicketRush " + service).version("v1")
                        .description("Errors are RFC 9457 problem details. Roles: CUSTOMER, ORGANIZER, ADMIN."))
                .servers(List.of(new Server().url("/").description("The API gateway")))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Keycloak realm ticketrush, client ticketrush-web")
                        .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                .authorizationUrl(oidc + "/auth")
                                .tokenUrl(oidc + "/token")
                                .scopes(new Scopes().addString("openid", "Sign in"))))))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
