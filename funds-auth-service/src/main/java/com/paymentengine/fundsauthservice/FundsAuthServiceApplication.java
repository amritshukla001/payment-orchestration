package com.payflow.fundsauthservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class FundsAuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FundsAuthServiceApplication.class, args);
    }
}
