-- 给 alarm_id 加唯一索引。
--
-- 原因：上传 OSS 成功但入库失败时，本地文件会被保留，下次任务会重跑同一个 alarmId，
-- 产生重复的归档记录；读取侧一旦用 selectOneByExample 就会直接抛异常。
--
-- 注意：如果表里已经有重复数据，先执行下面的清理语句（保留每个 alarm_id 最早的一条），
-- 否则加索引会失败。请先在测试库验证。
--
-- DELETE t1 FROM video_play_back t1
--   JOIN video_play_back t2
--     ON t1.alarm_id = t2.alarm_id
--    AND t1.create_time > t2.create_time
-- ;

ALTER TABLE video_play_back
    ADD CONSTRAINT uk_video_play_back_alarm_id UNIQUE (alarm_id);