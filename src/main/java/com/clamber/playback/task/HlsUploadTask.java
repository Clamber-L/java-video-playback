package com.clamber.playback.task;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PutObjectRequest;
import com.clamber.playback.domain.VideoPlayBack;
import com.clamber.playback.domain.mapper.VideoPlayBackMapper;
import com.clamber.playback.exception.ClamberException;
import com.clamber.playback.oss.OssConfig;
import com.clamber.playback.service.RtspToHlsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tk.mybatis.mapper.entity.Example;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HlsUploadTask {

	/**
	 * 雪花 ID 生成器。
	 * workerId / datacenterId 取自配置，多实例部署时必须给每个实例配不同的值，
	 * 否则随机取值可能撞号产生重复主键。
	 */
	private final Snowflake snowflake;

	private final OSS ossClient;
	private final OssConfig ossConfig;
	private final VideoPlayBackMapper videoPlayBackMapper;

	/** 与 RtspToHlsService 共用同一个可配置目录，避免两处硬编码不一致 */
	private final String baseDir;

	private final String bucketName;

	/** 用来判断某个 alarmId 是否正在拉流 */
	private final RtspToHlsService rtspToHlsService;

	public HlsUploadTask(OSS ossClient, OssConfig ossConfig, VideoPlayBackMapper videoPlayBackMapper,
	                     RtspToHlsService rtspToHlsService,
	                     @Value("${camera.hls.base-dir:/data/camera/hls/}") String baseDir,
	                     @Value("${aliyun.oss.bucket-name}") String bucketName,
	                     @Value("${camera.snowflake.worker-id:0}") long workerId,
	                     @Value("${camera.snowflake.datacenter-id:0}") long datacenterId) {
		this.ossClient = ossClient;
		this.ossConfig = ossConfig;
		this.videoPlayBackMapper = videoPlayBackMapper;
		this.rtspToHlsService = rtspToHlsService;
		this.baseDir = baseDir.endsWith("/") ? baseDir : baseDir + "/";
		this.bucketName = bucketName;
		this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
	}

	// 每天凌晨12点执行
	// @Scheduled(cron = "0 0 0 * * ?")
	public void uploadHls() {
		File root = new File(baseDir);
		if (!root.exists() || !root.isDirectory()) {
			log.info("HLS 目录不存在：{}", baseDir);
			return;
		}

		File[] alarmDirs = root.listFiles(File::isDirectory);
		if (alarmDirs == null || alarmDirs.length == 0) {
			log.info("没有任何 alarmId 目录");
			return;
		}

		int success = 0, skip = 0, fail = 0;
		for (File alarmDir : alarmDirs) {
			try {
				if (uploadAlarmDir(alarmDir)) {
					success++;
				} else {
					skip++;
				}
			} catch (Exception e) {
				fail++;
				log.error("上传失败，alarmId：{}，原因：{}", alarmDir.getName(), e.getMessage(), e);
			}
		}
		log.info("HLS 上传完成，成功：{}，跳过：{}，失败：{}", success, skip, fail);
	}

	/**
	 * @return true 表示完成了一次归档；false 表示本次跳过（未就绪，不是错误）
	 */
	private boolean uploadAlarmDir(File alarmDir) throws IOException {
		String alarmId = alarmDir.getName();
		File m3u8File = new File(alarmDir, "index.m3u8");

		if (!m3u8File.exists()) {
			log.warn("m3u8 文件不存在，跳过：{}", alarmDir.getPath());
			return false;
		}

		// 正在拉流的目录绝不能归档：ffmpeg 还在往里写，
		// 传上去的是残缺内容，而且下面会把本地文件删掉，直接破坏正在进行的拉流。
		if (rtspToHlsService.isActive(alarmId)) {
			log.info("alarmId {} 正在拉流中，本次跳过归档", alarmId);
			return false;
		}

		// 没有 #EXT-X-ENDLIST 说明这段录像没有正常收尾（进程被杀 / 崩溃 / 仍在写）。
		// 此时归档会把一段残缺录像固化到 OSS 并删掉本地文件，不可恢复，所以宁可留着等下次。
		if (!RtspToHlsService.isComplete(m3u8File)) {
			log.warn("alarmId {} 的 m3u8 未正常收尾（无 #EXT-X-ENDLIST），本次跳过归档", alarmId);
			return false;
		}

		// 已归档过就不要重复传、重复入库（上传成功但入库失败的重跑场景）
		if (findByAlarmId(alarmId) != null) {
			log.info("alarmId {} 已存在归档记录，跳过上传，仅清理本地文件", alarmId);
			deleteLocalFiles(alarmDir, m3u8File, listTsFiles(alarmDir));
			return false;
		}

		File[] tsFiles = listTsFiles(alarmDir);
		if (tsFiles == null || tsFiles.length == 0) {
			log.warn("没有 ts 文件，跳过：{}", alarmDir.getPath());
			return false;
		}

		List<String> tsNames = new ArrayList<>();
		for (File tsFile : tsFiles) {
			String ossKey = buildOssKey(alarmId, tsFile.getName());
			uploadFile(ossKey, tsFile);
			tsNames.add(tsFile.getName());
			log.debug("ts 上传成功：{}", ossKey);
		}

		String m3u8Content = new String(Files.readAllBytes(m3u8File.toPath()), StandardCharsets.UTF_8);
		String rewritten = rewriteM3u8(m3u8Content, alarmId, tsNames);

		String m3u8OssKey = buildOssKey(alarmId, "index.m3u8");
		byte[] m3u8Bytes = rewritten.getBytes(StandardCharsets.UTF_8);
		ossClient.putObject(bucketName, m3u8OssKey, new ByteArrayInputStream(m3u8Bytes));

		String playUrl = ossConfig.buildPublicUrl(m3u8OssKey);
		log.info("alarmId {} 上传完成，m3u8 地址：{}", alarmId, playUrl);

		// 入库成功后再删除本地文件
		VideoPlayBack record = new VideoPlayBack();
		record.setId(String.valueOf(snowflake.nextId()));
		record.setAlarmId(alarmId);
		record.setPlayUrl(playUrl);
		record.setCreateTime(LocalDateTime.now());
		record.setUpdateTime(LocalDateTime.now());
		int rows = videoPlayBackMapper.insertSelective(record);
		if (rows != 1) {
			throw new ClamberException("入库失败，alarmId：" + alarmId);
		}

		deleteLocalFiles(alarmDir, m3u8File, tsFiles);
		return true;
	}

	/**
	 * 把 m3u8 中的切片引用改写成 OSS 绝对地址。
	 *
	 * 不能用「整行等于文件名」来匹配：ffmpeg 的 -hls_segment_filename 带了目录，
	 * m3u8 里写的可能是 /data/camera/hls/{alarmId}/000.ts 这样的路径而非裸文件名。
	 * 这里按「行尾的文件名」匹配，两种写法都能覆盖。
	 * 同时用 Pattern.quote 转义，避免文件名里的 '.' 被当成正则通配符。
	 */
	private String rewriteM3u8(String content, String alarmId, List<String> tsNames) {
		String result = content;
		for (String tsName : tsNames) {
			String ossUrl = ossConfig.buildPublicUrl(buildOssKey(alarmId, tsName));
			// 匹配一整行、且以该文件名结尾（前面允许有任意目录前缀）
			Pattern p = Pattern.compile("(?m)^.*?" + Pattern.quote(tsName) + "\\s*$");
			result = p.matcher(result).replaceAll(Matcher.quoteReplacement(ossUrl));
		}

		// 改写后如果还残留本地路径，说明匹配没覆盖到，必须让本次归档失败，
		// 否则会把一个播不了的 m3u8 固化到 OSS 并删掉本地源文件。
		if (result.contains(baseDir)) {
			throw new ClamberException("m3u8 改写后仍残留本地路径，alarmId：" + alarmId
					+ "，请检查 ffmpeg 的 hls_segment_filename 与改写逻辑是否匹配");
		}
		return result;
	}

	private File[] listTsFiles(File alarmDir) {
		return alarmDir.listFiles(f -> f.isFile() && f.getName().endsWith(".ts"));
	}

	/**
	 * 删除本地文件。delete() 的返回值必须检查，否则失败时会静默残留。
	 */
	private void deleteLocalFiles(File alarmDir, File m3u8File, File[] tsFiles) {
		if (tsFiles != null) {
			for (File tsFile : tsFiles) {
				if (!tsFile.delete()) {
					log.warn("ts 文件删除失败：{}", tsFile.getPath());
				}
			}
		}
		if (m3u8File.exists() && !m3u8File.delete()) {
			log.warn("m3u8 删除失败：{}", m3u8File.getPath());
		}
		// 目录里可能还有其他文件（比如日志），删不掉是正常的，只记录不报错
		if (!alarmDir.delete()) {
			log.warn("目录未能删除（可能仍有其他文件）：{}", alarmDir.getPath());
		}
	}

	private VideoPlayBack findByAlarmId(String alarmId) {
		Example example = new Example(VideoPlayBack.class);
		example.createCriteria().andEqualTo("alarmId", alarmId);
		List<VideoPlayBack> records = videoPlayBackMapper.selectByExample(example);
		return (records == null || records.isEmpty()) ? null : records.get(0);
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