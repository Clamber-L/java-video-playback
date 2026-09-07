package com.clamber.playback.service;

import com.clamber.playback.domain.PlaybackResult;
import com.clamber.playback.domain.VideoPlayBack;
import com.clamber.playback.domain.mapper.VideoPlayBackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import com.clamber.playback.exception.ClamberException;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
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

	private static final DateTimeFormatter HIK_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final DateTimeFormatter DAHUA_FMT = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

	/** 强杀前额外留出的缓冲秒数 */
	private static final long KILL_GRACE_SECONDS = 5;

	private final VideoPlayBackMapper videoPlayBackMapper;
	private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

	private final String baseDir;

	/**
	 * URL 中解析不出时长时的兜底最大拉流时长（秒）。
	 * 没有这个上限，ffmpeg 可能一直挂着转码直到流自己断开，实际可能永不结束。
	 */
	private final long maxDurationSeconds;

	public RtspToHlsService(VideoPlayBackMapper videoPlayBackMapper,
	                        @Value("${camera.hls.base-dir:/data/camera/hls/}") String baseDir,
	                        @Value("${camera.hls.max-duration-seconds:3600}") long maxDurationSeconds) {
		this.videoPlayBackMapper = videoPlayBackMapper;
		this.baseDir = baseDir.endsWith("/") ? baseDir : baseDir + "/";
		this.maxDurationSeconds = maxDurationSeconds;
	}

	private static final Pattern HIKVISION_PATTERN = Pattern.compile(
			"^rtsp://.+/Streaming/tracks/\\d+01\\?starttime=(\\d{8}T\\d{6}Z)(&endtime=(\\d{8}T\\d{6}Z))?$"
	);
	private static final Pattern DAHUA_PATTERN = Pattern.compile(
			"^rtsp://.+/cam/playback\\?.*channel=\\d+.*&starttime=\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}&endtime=\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}.*$"
	);

	public PlaybackResult startPlayback(String rtspUrl, String alarmId) throws IOException {

		String fullDir = baseDir + alarmId + "/";

		// 已经在拉流中，直接返回
		if (activeProcesses.containsKey(alarmId)) {
			log.info("视频正在拉流中，直接返回");
			return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
		}

		// 本地已有播放完整的文件，直接返回
		File m3u8File = new File(fullDir + "index.m3u8");
		if (m3u8File.exists() && isComplete(m3u8File)) {
			log.info("本地已存在完整文件，直接返回");
			return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
		}

		// 查数据库是否已上传至 OSS
		VideoPlayBack record = findByAlarmId(alarmId);
		if (record != null) {
			log.info("视频已上传至 OSS，直接返回");
			return new PlaybackResult("oss", record.getPlayUrl());
		}

		log.info("开始拉流并生成 HLS 文件");
		validateRtspUrl(rtspUrl);

		long parsed = parseDuration(rtspUrl);
		// 解析不出时长时用兜底上限，避免 ffmpeg 无限期挂着
		long durationSeconds = parsed > 0 ? parsed : maxDurationSeconds;
		if (parsed <= 0) {
			log.warn("URL 中无法解析出时长，使用兜底上限 {} 秒，alarmId：{}", maxDurationSeconds, alarmId);
		}

		File dir = new File(fullDir);

		// 走到这里说明本地没有「录制完成」的文件，但可能残留着上次崩溃/被强杀留下的切片。
		// 必须先清掉：ffmpeg 带 -hls_flags append_list，否则会把新切片追加到旧 playlist 上，
		// 与新一轮的 %03d.ts 编号混在一起，播出来是两次拉流的混合内容。
		cleanStaleSegments(dir);

		// computeIfAbsent 保证「检查 + 启动进程 + 放入 map」是原子的：
		// 同一 alarmId 并发请求时只会有一个 ffmpeg 进程，不会两个进程写同一批 %03d.ts 互相覆盖。
		boolean[] created = {false};
		Process process;
		try {
			process = activeProcesses.computeIfAbsent(alarmId, key -> {
				try {
					ProcessBuilder builder = buildFfmpegProcess(rtspUrl, dir, fullDir, durationSeconds);
					builder.redirectErrorStream(true);
					builder.redirectOutput(new File("/home/log/ffmpeg-" + key + ".log"));
					Process started = builder.start();
					created[0] = true;
					return started;
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		if (created[0]) {
			// 超时强杀兜底
			scheduler.schedule(() -> {
				if (process.isAlive()) {
					log.warn("ffmpeg 超时，强制结束，alarmId：{}", alarmId);
					process.destroyForcibly();
				}
				activeProcesses.remove(alarmId, process);
			}, durationSeconds + KILL_GRACE_SECONDS, TimeUnit.SECONDS);

			// 进程自然结束时及时摘掉 key（不必等到强杀时刻）
			Thread waiter = new Thread(() -> {
				try {
					process.waitFor();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				} finally {
					activeProcesses.remove(alarmId, process);
				}
			}, "ffmpeg-waiter-" + alarmId);
			waiter.setDaemon(true);
			waiter.start();
		}

		return new PlaybackResult("local", "/hls/" + alarmId + "/index.m3u8");
	}

	/**
	 * 主动停止某路回放
	 */
	public void stopPlayback(String alarmId) {
		Process process = activeProcesses.get(alarmId);
		if (process != null) {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
			activeProcesses.remove(alarmId, process);
		}
	}

	/**
	 * 该 alarmId 当前是否正在拉流。
	 * 归档任务据此跳过，避免上传写到一半的 m3u8 / 切片。
	 */
	public boolean isActive(String alarmId) {
		Process process = activeProcesses.get(alarmId);
		return process != null && process.isAlive();
	}

	/**
	 * 应用关闭时收尾：否则正在拉流的 ffmpeg 会变成孤儿进程继续占用 CPU 转码。
	 */
	@PreDestroy
	public void shutdown() {
		log.info("应用关闭，正在结束 {} 个 ffmpeg 进程", activeProcesses.size());
		activeProcesses.forEach((alarmId, process) -> {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		});
		activeProcesses.clear();
		scheduler.shutdownNow();
	}

	/**
	 * 清掉上一轮拉流残留的 m3u8 与 ts 切片。
	 * 只在确认本地没有「录制完成」的文件、即将重新拉流时调用。
	 */
	private void cleanStaleSegments(File dir) {
		File[] stale = dir.listFiles(f -> f.isFile()
				&& (f.getName().endsWith(".ts") || f.getName().endsWith(".m3u8")));
		if (stale == null || stale.length == 0) {
			return;
		}
		int deleted = 0;
		for (File f : stale) {
			if (f.delete()) {
				deleted++;
			} else {
				log.warn("残留文件删除失败：{}", f.getPath());
			}
		}
		log.info("清理上一轮残留的 HLS 文件 {} 个，目录：{}", deleted, dir.getPath());
	}

	/**
	 * 是否是一段「录制完成」的 HLS。
	 * 只判断有没有 .ts 是不够的：上次拉流中途崩溃会留下一个只有几片切片的残缺 m3u8，
	 * 之后所有请求都会命中这个分支，永远返回不完整的录像且不会重新拉流。
	 * ffmpeg 正常收尾才会写入 #EXT-X-ENDLIST。
	 *
	 * 归档任务复用同一判断，所以是 public static。
	 */
	public static boolean isComplete(File m3u8File) {
		try {
			String content = new String(Files.readAllBytes(m3u8File.toPath()), java.nio.charset.StandardCharsets.UTF_8);
			return content.contains("#EXT-X-ENDLIST") && content.contains(".ts");
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * 按 alarmId 查归档记录。
	 * 用 selectByExample 取第一条而不是 selectOneByExample：后者在存在重复记录时会直接抛异常，
	 * 而「上传成功但入库失败后重跑」等场景确实可能写入重复的 alarmId。
	 */
	private VideoPlayBack findByAlarmId(String alarmId) {
		Example example = new Example(VideoPlayBack.class);
		example.createCriteria().andEqualTo("alarmId", alarmId);
		List<VideoPlayBack> records = videoPlayBackMapper.selectByExample(example);
		if (records == null || records.isEmpty()) {
			return null;
		}
		if (records.size() > 1) {
			log.warn("alarmId {} 存在 {} 条归档记录，取第一条（建议给 alarm_id 加唯一索引）", alarmId, records.size());
		}
		return records.get(0);
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
			throw new ClamberException("RTSP 地址不能为空");
		}
		if (!rtspUrl.startsWith("rtsp://")) {
			throw new ClamberException("必须是 rtsp:// 开头的地址");
		}

		boolean isHikvision = HIKVISION_PATTERN.matcher(rtspUrl).matches();
		boolean isDahua = DAHUA_PATTERN.matcher(rtspUrl).matches();

		if (!isHikvision && !isDahua) {
			throw new ClamberException(
					"不支持的 RTSP 格式，仅支持：\n" +
							"海康格式：rtsp://ip/Streaming/tracks/[主码流]?starttime=...&endtime=...\n" +
							"大华格式：rtsp://ip/cam/playback?channel=x&subtype=0&starttime=yyyy_MM_dd_HH_mm_ss&endtime=yyyy_MM_dd_HH_mm_ss"
			);
		}

		if (isHikvision && rtspUrl.contains("/tracks/")) {
			String trackPart = rtspUrl.replaceAll(".*/tracks/(\\d+)\\?.*", "$1");
			if (!trackPart.endsWith("01")) {
				throw new ClamberException(
						"海康仅支持主码流回放，track 号末尾必须为 01（如 101、2601），当前传入：" + trackPart
				);
			}
		}
	}

	private static ProcessBuilder buildFfmpegProcess(String rtspUrl, File dir, String fullDir, long durationSeconds) throws IOException {
		if (!dir.exists() && !dir.mkdirs()) {
			throw new ClamberException("Failed to create directory: " + fullDir);
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