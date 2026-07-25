package com.paymentengine.complianceservice;

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
                title = "Payment Processing Engine compliance-service",
                description = "Evaluates CHECK_COMPLIANCE commands (KYC verification, UPI-directory "
                        + "checks) and records AML-style regulatory reports for payments above a "
                        + "configured threshold, regardless of verdict.",
                version = "v1"
        ),
        security = @SecurityRequirement(name = "apiKey")
)
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key")
public class ComplianceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}
