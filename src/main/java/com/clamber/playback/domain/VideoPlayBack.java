package com.clamber.playback.domain;

import com.clamber.playback.config.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 告警录像归档记录：alarmId -> OSS 播放地址。
 * 建议给 alarm_id 加唯一索引，避免上传成功但入库失败后重跑产生重复记录。
 */
@Setter
@Getter
@Table(name = "video_play_back")
public class VideoPlayBack extends BaseEntity {

	private String alarmId;

	private String playUrl;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;
}