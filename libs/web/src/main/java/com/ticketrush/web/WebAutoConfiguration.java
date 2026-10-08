package com.ticketrush.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Problem-details error responses for the services' REST APIs. Runs before Boot's MVC configuration, so
 * the handler Boot adds under {@code spring.mvc.problemdetails.enabled} backs off in favour of
 * {@link ApiExceptionHandler}.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@Import(ApiExceptionHandler.class)
public class WebAutoConfiguration {
}
