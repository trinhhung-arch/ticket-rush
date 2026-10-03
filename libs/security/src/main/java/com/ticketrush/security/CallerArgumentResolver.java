package com.ticketrush.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // A @Nullable Caller on a public endpoint means "whoever it is, if anyone".
            if (parameter.isOptional()) {
                return null;
            }
            throw new AuthenticationCredentialsNotFoundException("This endpoint needs a bearer token");
        }
        Jwt jwt = token.getToken();
        Set<String> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")), roles);
    }
}
