package com.clamber.playback.task;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PutObjectRequest;
import com.clamber.playback.domain.VideoPlayBack;
import com.clamber.playback.domain.mapper.VideoPlayBackMapper;
import com.clamber.playback.oss.OssConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class HlsUploadTask {

	private static final String BASE_DIR = "/data/camera/hls/";

	// 雪花id生成器
	public static final Snowflake SNOWFLAKE = IdUtil.getSnowflake(
		ThreadLocalRandom.current().nextInt(32),
		ThreadLocalRandom.current().nextInt(32)
	);

	private final OSS ossClient;
	private final OssConfig ossConfig;
	private final VideoPlayBackMapper videoPlayBackMapper;

	@Value("${aliyun.oss.bucket-name}")
	private String bucketName;

	public HlsUploadTask(OSS ossClient, OssConfig ossConfig, VideoPlayBackMapper videoPlayBackMapper) {
		this.ossClient = ossClient;
		this.ossConfig = ossConfig;
		this.videoPlayBackMapper = videoPlayBackMapper;
	}

	// 每天凌晨12点执行
	@Scheduled(cron = "0 0 0 * * ?")
	public void uploadHls() {
		File baseDir = new File(BASE_DIR);
		if (!baseDir.exists() || !baseDir.isDirectory()) {
			log.info("HLS 目录不存在：{}", BASE_DIR);
			return;
		}

		File[] alarmDirs = baseDir.listFiles(File::isDirectory);
		if (alarmDirs == null || alarmDirs.length == 0) {
			log.info("没有任何 alarmId 目录");
			return;
		}

		int success = 0, fail = 0;
		for (File alarmDir : alarmDirs) {
			try {
				uploadAlarmDir(alarmDir);
				success++;
			} catch (Exception e) {
				fail++;
				log.error("上传失败，alarmId：{}，原因：{}", alarmDir.getName(), e.getMessage(), e);
			}
		}
		log.info("HLS 上传完成，成功：{}，失败：{}", success, fail);
	}

	private void uploadAlarmDir(File alarmDir) throws IOException {
		String alarmId = alarmDir.getName();
		File m3u8File = new File(alarmDir, "index.m3u8");

		if (!m3u8File.exists()) {
			log.warn("m3u8 文件不存在，跳过：{}", alarmDir.getPath());
			return;
		}

		File[] tsFiles = alarmDir.listFiles(f -> f.getName().endsWith(".ts"));
		if (tsFiles == null || tsFiles.length == 0) {
			log.warn("没有 ts 文件，跳过：{}", alarmDir.getPath());
			return;
		}

		List<String> tsNames = new ArrayList<>();
		for (File tsFile : tsFiles) {
			String ossKey = buildOssKey(alarmId, tsFile.getName());
			uploadFile(ossKey, tsFile);
			tsNames.add(tsFile.getName());
			log.debug("ts 上传成功：{}", ossKey);
		}

		String m3u8Content = new String(Files.readAllBytes(m3u8File.toPath()), StandardCharsets.UTF_8);
		for (String tsName : tsNames) {
			String ossUrl = ossConfig.getEndpoint() + "/" + buildOssKey(alarmId, tsName);
			m3u8Content = m3u8Content.replaceAll("(?m)^" + tsName + "$", ossUrl);
		}

		String m3u8OssKey = buildOssKey(alarmId, "index.m3u8");
		byte[] m3u8Bytes = m3u8Content.getBytes(StandardCharsets.UTF_8);
		ossClient.putObject(bucketName, m3u8OssKey, new java.io.ByteArrayInputStream(m3u8Bytes));

		log.info("alarmId {} 上传完成，m3u8 地址：{}/{}", alarmId, ossConfig.getEndpoint(), m3u8OssKey);

		// 入库成功后再删除本地文件
		VideoPlayBack record = new VideoPlayBack();
		record.setId(String.valueOf(SNOWFLAKE.nextId()));
		record.setAlarmId(alarmId);
		record.setPlayUrl(ossConfig.getEndpoint() + "/" + m3u8OssKey);
		record.setCreateTime(LocalDateTime.now());
		record.setUpdateTime(LocalDateTime.now());
		int rows = videoPlayBackMapper.insertSelective(record);
		if (rows != 1) {
			throw new IOException("入库失败，alarmId：" + alarmId);
		}

		// 删除本地文件
		for (File tsFile : tsFiles) {
			tsFile.delete();
		}
		m3u8File.delete();
		alarmDir.delete();
	}

	private void uploadFile(String ossKey, File file) throws IOException {
		try (FileInputStream fis = new FileInputStream(file)) {
			ossClient.putObject(new PutObjectRequest(bucketName, ossKey, fis));
		}
	}

	private String buildOssKey(String alarmId, String fileName) {
		return "camera/hls/" + alarmId + "/" + fileName;
	}
}