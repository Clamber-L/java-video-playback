package com.clamber.playback.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 把 ffmpeg 落在本地磁盘的 HLS 文件暴露成 HTTP 静态资源。
 *
 * startPlayback 返回的 local 地址是 /hls/{alarmId}/index.m3u8，
 * 如果没有这个映射，该地址在应用内是 404，只能依赖外层 Nginx 反代 —— 这里让服务自身即可播放。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final String baseDir;

	public WebMvcConfig(@Value("${camera.hls.base-dir:/data/camera/hls/}") String baseDir) {
		this.baseDir = baseDir.endsWith("/") ? baseDir : baseDir + "/";
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// 必须以 / 结尾，否则 Spring 无法把 /hls/{alarmId}/index.m3u8 正确解析到目录下；
		// File.toURI() 对不存在的目录不会补斜杠，这里显式补上。
		String location = new File(baseDir).toURI().toString();
		if (!location.endsWith("/")) {
			location = location + "/";
		}
		registry.addResourceHandler("/hls/**")
				.addResourceLocations(location)
				// m3u8 / ts 在拉流过程中会持续增长，不能让浏览器缓存
				.setCachePeriod(0);
		log.info("已将 /hls/** 映射到本地目录 {}", location);
	}
}