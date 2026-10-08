package com.ticketrush.security;

import java.lang.reflect.AnnotatedElement;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks {@link CustomerOnly} and {@link OrganizerOnly} before the request body is read. Method
 * security alone runs after binding and validation, so a caller without the role would get a 400
 * describing the validation rules instead of a plain 403. The {@code @PreAuthorize} on those
 * annotations stays as a second check.
 */
class RoleCheckInterceptor implements HandlerInterceptor {

    private static final Set<String> CUSTOMER = Set.of("ROLE_" + Roles.CUSTOMER);
    private static final Set<String> ORGANIZER = Set.of("ROLE_" + Roles.ORGANIZER, "ROLE_" + Roles.ADMIN);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Set<String> allowed = allowedRoles(method);
        if (allowed == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new AuthenticationCredentialsNotFoundException("This endpoint needs a bearer token");
        }
        boolean permitted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(allowed::contains);
        if (!permitted) {
            throw new AccessDeniedException("Missing role for " + request.getRequestURI());
        }
        return true;
    }

    /** The method's own annotation wins over the controller's. */
    private static Set<String> allowedRoles(HandlerMethod method) {
        for (AnnotatedElement annotated : new AnnotatedElement[] {method.getMethod(), method.getBeanType()}) {
            if (AnnotatedElementUtils.hasAnnotation(annotated, OrganizerOnly.class)) {
                return ORGANIZER;
            }
            if (AnnotatedElementUtils.hasAnnotation(annotated, CustomerOnly.class)) {
                return CUSTOMER;
            }
        }
        return null;
    }
}
