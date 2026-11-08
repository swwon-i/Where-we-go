package com.wherewego;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WhereWeGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhereWeGoApplication.class, args);
    }
}
