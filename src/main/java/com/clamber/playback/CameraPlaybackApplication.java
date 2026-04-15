package com.clamber.playback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import tk.mybatis.spring.annotation.MapperScan;

@EnableScheduling
@SpringBootApplication
@MapperScan("com.clamber.playback.domain.mapper")
public class CameraPlaybackApplication {

	public static void main(String[] args) {
		SpringApplication.run(CameraPlaybackApplication.class, args);
	}

}
