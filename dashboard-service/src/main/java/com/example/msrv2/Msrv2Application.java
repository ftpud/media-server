package com.example.msrv2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class Msrv2Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(Msrv2Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Msrv2Application.class, args);
    }

}
