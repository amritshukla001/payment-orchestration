package com.payflow.fundsauthservice;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@OpenAPIDefinition(
        info = @Info(
                title = "PayFlow funds-auth-service",
                description = "Mock bank: reserves and releases funds against a simulated account balance. "
                        + "NetBanking payments also go through a bank-availability check (see "
                        + "NetBankingAvailabilityRule) -- a demo lever for marking a mock bank down.",
                version = "v1"
        ),
        security = @SecurityRequirement(name = "apiKey")
)
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key")
public class FundsAuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FundsAuthServiceApplication.class, args);
    }
}
