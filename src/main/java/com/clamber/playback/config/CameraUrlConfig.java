package com.clamber.playback.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@SpringBootConfiguration
@ConfigurationProperties(prefix = "camera")
public class CameraUrlConfig {

	private String hk;

	private String dh;
}
