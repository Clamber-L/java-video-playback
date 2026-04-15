package com.clamber.playback.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PlaybackResult {

    /** local: 本地拉流地址（相对路径，前端自拼 ip:port）; oss: OSS 完整地址 */
    private final String source;

    private final String url;
}
