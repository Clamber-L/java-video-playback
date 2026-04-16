package com.clamber.playback.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
public class OssConfig {

	public String getEndpoint() {
		return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
	}

	@Value("${aliyun.oss.endpoint}")
	private String endpoint;

	@Value("${aliyun.oss.access-key-id}")
	private String accessKeyId;

	@Value("${aliyun.oss.access-key-secret}")
	private String accessKeySecret;

	@Bean
	public OSS ossClient() {
		return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
	}
}

