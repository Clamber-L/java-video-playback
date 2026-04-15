package com.clamber.playback.domain;

import com.clamber.playback.config.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class VideoPlayBack extends BaseEntity {

	private String alarmId;

	private String playUrl;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;
}
