package com.sds.communicators.io;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IoApplication {
    public static void main(String[] args) {
        SpringApplication.run(IoApplication.class, args);
    }
}
