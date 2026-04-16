# 摄像头回放服务 - 业务流程报告

## 一、整体架构概述

本项目是一个摄像头视频回放服务，基于 Spring Boot 构建，核心职责是将摄像头的 RTSP 回放流转换为 HLS 格式供前端播放，并在每日凌晨将本地 HLS 文件上传至阿里云 OSS 做持久化存储。

主要组件分为四层：

- 控制层：PlaybackController，对外暴露 HTTP 接口
- 服务层：RtspToHlsService，负责拉流、转码、进程管理
- 定时任务层：HlsUploadTask，负责 HLS 文件上传 OSS 及入库
- 基础设施层：OssConfig（阿里云 OSS 客户端配置）、VideoPlayBackMapper（MyBatis 数据库操作）

数据实体：
- `VideoPlayBack`：存储 alarmId 与 OSS 播放地址的映射关系，含 id（雪花ID）、alarmId、playUrl、createTime、updateTime
- `PlaybackResult`：接口返回值，包含 source（"local" 或 "oss"）和 url（播放地址）

---

## 二、视频回放请求流程

入口：`GET /playback/start?rtspUrl=xxx&alarmId=yyy`

```
请求进入
  │
  ├─ activeProcesses 中存在该 alarmId？
  │     └─ 是 → 返回 local:/hls/{alarmId}/index.m3u8
  │
  ├─ 本地 index.m3u8 存在 且 包含 .ts 内容？
  │     └─ 是 → 返回 local:/hls/{alarmId}/index.m3u8
  │
  ├─ 数据库中存在该 alarmId 的记录？
  │     └─ 是 → 返回 oss:{playUrl}
  │
  └─ 以上均否 → 校验 RTSP 地址 → 解析时长 → 启动 FFmpeg 进程
                                                │
                              ┌─────────────────┴──────────────────┐
                         有时长限制                              无时长限制
                    ScheduledExecutor                        守护线程等待
                    durationSeconds+5s 后强杀              进程自然结束后清理
                              └─────────────────┬──────────────────┘
                                         返回 local:/hls/{alarmId}/index.m3u8
```

### RTSP 地址校验规则

- 不能为空，必须以 `rtsp://` 开头
- 必须匹配海康或大华格式之一
  - 海康：`rtsp://ip/Streaming/tracks/{trackId}?starttime=...`，track 号末尾必须为 `01`（主码流）
  - 大华：`rtsp://ip/cam/playback?channel=x&starttime=yyyy_MM_dd_HH_mm_ss&endtime=...`

### FFmpeg 转码参数

| 参数 | 值 |
|------|-----|
| 传输协议 | TCP |
| 视频编码 | libx264，preset ultrafast，crf 23 |
| 音频 | 无（-an） |
| HLS 切片时长 | 2 秒 |
| 切片保留数量 | 全部（list_size 0） |
| 切片文件名 | 000.ts、001.ts... |

---

## 三、定时上传任务流程

触发方式：
- 定时触发：每天凌晨 `00:00:00`（cron = "0 0 0 * * ?"）
- 手动触发：`GET /playback/upload`

```
扫描 /data/camera/hls/ 下所有子目录
  │
  └─ 遍历每个 alarmId 目录（单个失败不影响其他）
        │
        ├─ index.m3u8 不存在 → 跳过
        ├─ 无 .ts 文件 → 跳过
        │
        ├─ 逐个上传 .ts 文件到 OSS
        │     OSS Key：camera/hls/{alarmId}/{fileName}
        │
        ├─ 读取本地 m3u8，将 ts 文件名替换为 OSS 完整 URL
        │
        ├─ 上传改写后的 m3u8 到 OSS
        │     OSS Key：camera/hls/{alarmId}/index.m3u8
        │
        ├─ 写入数据库（VideoPlayBack）
        │     insertSelective 返回值 != 1 → 抛异常，本地文件保留
        │
        └─ 入库成功 → 删除本地 .ts、index.m3u8、alarmId 目录
```

---

## 四、数据流向

```
FFmpeg 写入本地
  /data/camera/hls/{alarmId}/000.ts
  /data/camera/hls/{alarmId}/001.ts
  /data/camera/hls/{alarmId}/index.m3u8（相对路径引用 ts）
        │
        ▼
HlsUploadTask 上传 OSS
  OSS: camera/hls/{alarmId}/000.ts
  OSS: camera/hls/{alarmId}/001.ts
  OSS: camera/hls/{alarmId}/index.m3u8（绝对 URL 引用 ts）
        │
        ▼
数据库写入
  video_play_back.play_url = {endpoint}/camera/hls/{alarmId}/index.m3u8
        │
        ▼
本地文件清理
  删除 ts、m3u8、目录
```

---

## 五、异常处理机制

| 场景 | 处理方式 |
|------|---------|
| RTSP 地址非法 | 抛 IllegalArgumentException，返回 400 |
| FFmpeg 目录创建失败 | 抛 IOException，向上传播 |
| 单个 alarmId 上传失败 | catch Exception，记录日志，继续处理下一个 |
| 入库失败 | 抛 IOException，本地文件保留，不删除 |
| FFmpeg 超时 | ScheduledExecutor 在时长+5s 后 destroyForcibly |
| m3u8 读取失败 | hasSegments 返回 false，触发重新拉流 |

---

## 六、已知注意事项与潜在问题

**1. m3u8 替换正则未转义**
使用 `"(?m)^" + tsName + "$"` 做替换，若 ts 文件名含正则特殊字符（如 `.`）会导致匹配异常。
建议改为 `Pattern.quote(tsName)`。

**2. 定时任务未跳过正在拉流的 alarmId**
FFmpeg 写入期间若凌晨任务触发，可能上传不完整的 HLS 文件。
建议上传前检查 `activeProcesses` 中是否存在该 alarmId。

**3. 入库失败后下次任务会重复上传**
本地文件保留后，下次凌晨任务会再次上传同一 alarmId，可能导致 OSS 重复写入（覆盖）。

**4. 雪花 ID 多实例部署存在冲突风险**
`SNOWFLAKE` 在类加载时随机生成节点 ID，多实例部署时可能产生 ID 冲突。

**5. stopPlayback 未暴露 HTTP 接口**
`RtspToHlsService.stopPlayback()` 方法存在，但 Controller 中没有对应路由。

**6. FFmpeg inheritIO 会污染服务日志**
生产环境大量拉流会导致日志混乱，建议重定向到独立日志文件。

**7. OSS endpoint 末尾斜杠问题**
若 endpoint 末尾带有 `/`，拼接出的 URL 会出现双斜杠，需做规范化处理。
