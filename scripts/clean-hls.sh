#!/bin/bash
#
# 清理 HLS 回放目录：只保留最近 RETAIN_DAYS 天的 alarmId 目录，更早的整目录删除。
#
# 部署（CentOS）：
#   install -m 755 clean-hls.sh /usr/local/bin/clean-hls.sh
#   crontab -e   ->   30 3 * * * /usr/local/bin/clean-hls.sh >> /var/log/clean-hls.log 2>&1
#
# 先空跑确认要删哪些：DRY_RUN=1 /usr/local/bin/clean-hls.sh
#

set -uo pipefail

# 与 application.yml 的 camera.hls.base-dir 保持一致
BASE_DIR="${BASE_DIR:-/data/camera/hls}"
RETAIN_DAYS="${RETAIN_DAYS:-3}"
DRY_RUN="${DRY_RUN:-0}"

log() { echo "[$(date '+%F %T')] $*"; }

# 防误删：路径必须是绝对路径、层级足够深，且真实存在。
# 少了这几道判断，一旦 BASE_DIR 被写空就会从 / 开始删。
case "$BASE_DIR" in
	/*) ;;
	*) log "BASE_DIR 必须是绝对路径：$BASE_DIR"; exit 1 ;;
esac
BASE_DIR="${BASE_DIR%/}"
if [ "$(echo "$BASE_DIR" | awk -F/ '{print NF-1}')" -lt 2 ]; then
	log "BASE_DIR 层级过浅，拒绝执行：$BASE_DIR"
	exit 1
fi
if [ ! -d "$BASE_DIR" ]; then
	log "目录不存在，无需清理：$BASE_DIR"
	exit 0
fi

# 必须先确认是纯数字：非数字传进 [ -lt ] 只会报错并返回非 0，
# 结果是校验被「通过」，随后 MTIME_ARG 算出个空值，find 会去删全部目录。
case "$RETAIN_DAYS" in
	''|*[!0-9]*) log "RETAIN_DAYS 必须是正整数：$RETAIN_DAYS"; exit 1 ;;
esac
if [ "$RETAIN_DAYS" -lt 1 ]; then
	log "RETAIN_DAYS 必须 >= 1：$RETAIN_DAYS"
	exit 1
fi

# find 的 -mtime 以 24 小时为单位向下取整：+N 命中「至少 N+1 个 24 小时」的目录。
# 要保留最近 3 天（含今天），就得删「满 3×24 小时」的，即 -mtime +2。
MTIME_ARG=$((RETAIN_DAYS - 1))

# 目录 mtime 会随目录内文件的增删而更新，所以正在拉流写入的目录 mtime 一定是新的，
# 不会被这里命中；不需要额外判断 ffmpeg 是否在跑。
deleted=0
failed=0
while IFS= read -r -d '' dir; do
	if [ "$DRY_RUN" = "1" ]; then
		log "[dry-run] 将删除：$dir ($(du -sh "$dir" 2>/dev/null | cut -f1))"
		deleted=$((deleted + 1))
		continue
	fi
	if rm -rf -- "$dir"; then
		log "已删除：$dir"
		deleted=$((deleted + 1))
	else
		log "删除失败：$dir"
		failed=$((failed + 1))
	fi
done < <(find "$BASE_DIR" -mindepth 1 -maxdepth 1 -type d -mtime "+$MTIME_ARG" -print0)

log "清理完成：保留 ${RETAIN_DAYS} 天，删除 ${deleted} 个目录，失败 ${failed} 个"
exit 0
