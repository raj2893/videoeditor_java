package com.example.videoeditor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class VideoeditorApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoeditorApplication.class, args);
	}

}

