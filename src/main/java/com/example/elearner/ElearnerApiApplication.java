package com.example.elearner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ElearnerApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElearnerApiApplication.class, args);
    }
}
