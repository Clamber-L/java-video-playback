package com.clamber.playback.controller;

import com.clamber.playback.domain.PlaybackResult;
import com.clamber.playback.service.RtspToHlsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/playback")
public class PlaybackController {

	@Resource
	private RtspToHlsService service;

	/**
	 * 示例：
	 * /playback/start?rtspUrl=xxx
	 */
	@GetMapping("/start")
	public PlaybackResult start(@RequestParam String rtspUrl, String alarmId) throws Exception {
		return service.startPlayback(rtspUrl, alarmId);
	}
}
