package com.payflow.fraudservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class FraudServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
