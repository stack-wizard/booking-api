package com.stackwizard.booking_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.stackwizard.booking_api.config.MediaS3Properties;
import com.stackwizard.booking_api.security.JwtProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, MediaS3Properties.class})
public class BookingApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(BookingApiApplication.class, args);
	}

}
