package com.example.group_demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GroupDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(GroupDemoApplication.class, args);
	}

}
