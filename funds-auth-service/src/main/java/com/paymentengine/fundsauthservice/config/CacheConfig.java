package com.payflow.fundsauthservice.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Backs the account-balance cache-aside read in MockBankLedger.getBalance().
 * Spring Boot autoconfigures a Redis-backed CacheManager from
 * spring.cache.type: redis in application.yml -- this just turns on
 * @Cacheable/@CacheEvict processing.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
