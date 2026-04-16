package com.clamber.playback.service;

import com.clamber.playback.domain.PlaybackResult;
import com.clamber.playback.domain.VideoPlayBack;
import com.clamber.playback.domain.mapper.VideoPlayBackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class RtspToHlsService {

	private static final String BASE_DIR = "/data/camera/hls/";
	private static final DateTimeFormatter HIK_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final DateTimeFormatter DAHUA_FMT = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

	private final VideoPlayBackMapper videoPlayBackMapper;
	private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

	public RtspToHlsService(VideoPlayBackMapper videoPlayBackMapper) {
		this.videoPlayBackMapper = videoPlayBackMapper;
	}

	private static final Pattern HIKVISION_PATTERN = Pattern.compile(
			"^rtsp://.+/Streaming/tracks/\\d+01\\?starttime=(\\d{8}T\\d{6}Z)(&endtime=(\\d{8}T\\d{6}Z))?$"
	);
	private static final Pattern DAHUA_PATTERN = Pattern.compile(
			"^rtsp://.+/cam/playback\\?.*channel=\\d+.*&starttime=\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}&endtime=\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}.*$"
	);

	public PlaybackResult startPlayback(String rtspUrl, String alarmId) throws IOException {

		String fullDir = BASE_DIR + alarmId + "/";

		// 已经在拉流中，直接返回
		if (activeProcesses.containsKey(alarmId)) {
			log.info("视频正在拉流中，直接返回");
			return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
		}

		// 本地已有完整文件，直接返回
		File m3u8File = new File(fullDir + "index.m3u8");
		if (m3u8File.exists() && hasSegments(m3u8File)) {
			log.info("本地已存在完整文件，直接返回");
			return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
		}

		// 查数据库是否已上传至 OSS
		Example example = new Example(VideoPlayBack.class);
		example.createCriteria().andEqualTo("alarmId", alarmId);
		VideoPlayBack record = videoPlayBackMapper.selectOneByExample(example);
		if (record != null) {
			log.info("视频已上传至 OSS，直接返回");
			return new PlaybackResult("oss", record.getPlayUrl());
		}

		log.info("开始拉流并生成 HLS 文件");
		validateRtspUrl(rtspUrl);

		long durationSeconds = parseDuration(rtspUrl);

		File dir = new File(fullDir);
		ProcessBuilder builder = buildFfmpegProcess(rtspUrl, dir, fullDir, durationSeconds);
		builder.redirectErrorStream(true);
		builder.redirectOutput(new File("/home/log/ffmpeg-" + alarmId + ".log"));
		Process process = builder.start();

		activeProcesses.put(alarmId, process);

		if (durationSeconds > 0) {
			// 超时强杀，多留5秒缓冲
			scheduler.schedule(() -> {
				if (process.isAlive()) {
					process.destroyForcibly();
				}
				activeProcesses.remove(alarmId);
			}, durationSeconds + 5, TimeUnit.SECONDS);
		} else {
			// 无时长限制，等进程自然结束后清理
			new Thread(() -> {
				try { process.waitFor(); } catch (InterruptedException ignored) {}
				activeProcesses.remove(alarmId);
			}).start();
		}

		return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
	}

	/**
	 * 主动停止某路回放
	 */
	public void stopPlayback(String alarmId) {
		Process process = activeProcesses.get(alarmId);
		if (process != null && process.isAlive()) {
			process.destroyForcibly();
			activeProcesses.remove(alarmId);
		}
	}

	private boolean hasSegments(File m3u8File) {
		try {
			String content = new String(Files.readAllBytes(m3u8File.toPath()), java.nio.charset.StandardCharsets.UTF_8);
			return content.contains(".ts");
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * 从 URL 中解析 starttime/endtime，返回时长秒数；无法解析则返回 -1（不限时）
	 */
	private long parseDuration(String rtspUrl) {
		// 海康
		Matcher m = HIKVISION_PATTERN.matcher(rtspUrl);
		if (m.matches() && m.group(3) != null) {
			LocalDateTime start = LocalDateTime.parse(m.group(1), HIK_FMT);
			LocalDateTime end = LocalDateTime.parse(m.group(3), HIK_FMT);
			return ChronoUnit.SECONDS.between(start, end);
		}
		// 大华
		Pattern dahuaTime = Pattern.compile(
				"starttime=(\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}).*endtime=(\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2})"
		);
		Matcher dm = dahuaTime.matcher(rtspUrl);
		if (dm.find()) {
			try {
				LocalDateTime start = LocalDateTime.parse(dm.group(1), DAHUA_FMT);
				LocalDateTime end = LocalDateTime.parse(dm.group(2), DAHUA_FMT);
				return ChronoUnit.SECONDS.between(start, end);
			} catch (Exception ignored) {}
		}
		return -1;
	}

	private void validateRtspUrl(String rtspUrl) {
		if (!StringUtils.hasText(rtspUrl)) {
			throw new IllegalArgumentException("RTSP 地址不能为空");
		}
		if (!rtspUrl.startsWith("rtsp://")) {
			throw new IllegalArgumentException("必须是 rtsp:// 开头的地址");
		}

		boolean isHikvision = HIKVISION_PATTERN.matcher(rtspUrl).matches();
		boolean isDahua = DAHUA_PATTERN.matcher(rtspUrl).matches();

		if (!isHikvision && !isDahua) {
			throw new IllegalArgumentException(
					"不支持的 RTSP 格式，仅支持：\n" +
							"海康格式：rtsp://ip/Streaming/tracks/[主码流]?starttime=...&endtime=...\n" +
							"大华格式：rtsp://ip/cam/playback?channel=x&subtype=0&starttime=yyyy_MM_dd_HH_mm_ss&endtime=yyyy_MM_dd_HH_mm_ss"
			);
		}

		if (isHikvision && rtspUrl.contains("/tracks/")) {
			String trackPart = rtspUrl.replaceAll(".*/tracks/(\\d+)\\?.*", "$1");
			if (!trackPart.endsWith("01")) {
				throw new IllegalArgumentException(
						"海康仅支持主码流回放，track 号末尾必须为 01（如 101、2601），当前传入：" + trackPart
				);
			}
		}
	}

	private static ProcessBuilder buildFfmpegProcess(String rtspUrl, File dir, String fullDir, long durationSeconds) throws IOException {
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IOException("Failed to create directory: " + fullDir);
		}

		List<String> cmd = new ArrayList<>(Arrays.asList(
				"ffmpeg",
				"-rtsp_transport", "tcp",
				"-i", rtspUrl
		));

		if (durationSeconds > 0) {
			cmd.add("-t");
			cmd.add(String.valueOf(durationSeconds));
		}

		cmd.addAll(Arrays.asList(
				"-fflags", "+genpts",
				"-vsync", "0",
				"-c:v", "libx264",
				"-preset", "ultrafast",
				"-crf", "23",
				"-an",
				"-f", "hls",
				"-hls_time", "2",
				"-hls_list_size", "0",
				"-hls_flags", "append_list",
				"-hls_segment_filename", fullDir + "%03d.ts",
				fullDir + "index.m3u8"
		));

		return new ProcessBuilder(cmd);
	}
}