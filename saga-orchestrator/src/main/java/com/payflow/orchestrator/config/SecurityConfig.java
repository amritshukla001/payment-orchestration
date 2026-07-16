package com.payflow.orchestrator.config;

import com.payflow.common.security.ApiKeyAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            @Value("${payflow.security.api-key}") String apiKey) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyAuthFilter(apiKey, List.of("/actuator")));
        registration.addUrlPatterns("/*");
        return registration;
    }
}
