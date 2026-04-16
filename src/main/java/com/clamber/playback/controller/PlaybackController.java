package com.clamber.playback.controller;

import com.clamber.playback.domain.PlaybackResult;
import com.clamber.playback.service.RtspToHlsService;
import com.clamber.playback.task.HlsUploadTask;
import com.clamber.playback.utils.CommonResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.io.IOException;

@RestController
@RequestMapping("/playback")
public class PlaybackController {

	@Resource
	private RtspToHlsService service;

	@Resource
	private HlsUploadTask hlsUploadTask;

	/**
	 * 示例：
	 * /playback/start?rtspUrl=xxx
	 */
	@GetMapping("/start")
	public CommonResult<PlaybackResult> start(@RequestParam String rtspUrl, @RequestParam String alarmId) throws IOException {
		return CommonResult.success(service.startPlayback(rtspUrl, alarmId));
	}

	@GetMapping("/upload")
	public String triggerUpload() {
		hlsUploadTask.uploadHls();
		return "ok";
	}
}
