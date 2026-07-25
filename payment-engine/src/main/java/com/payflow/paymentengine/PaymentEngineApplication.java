package com.payflow.paymentengine;

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
                title = "PayFlow payment-engine",
                description = "Owns the payment engine's state machine. Read APIs fold the "
                        + "event-sourced payment_saga_events log into a payment's live state, "
                        + "plus on-demand AI incident summaries for compensated payments.",
                version = "v1"
        ),
        security = @SecurityRequirement(name = "apiKey")
)
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key")
public class PaymentEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentEngineApplication.class, args);
    }
}
