#!/system/bin/sh
# Read-only Enco X4 capability report for MT Manager / rooted Android.
# Does not read bt_config.conf, btsnoop captures, pairing keys or system accounts.
set -u
if [ "$(id -u)" != 0 ]; then
 echo '需要 Root：请在 MT 管理器中选择以 Root 权限执行。'
 exit 1
fi
PREPARE_SECONDS=${1:-30}
case "$PREPARE_SECONDS" in
 ''|*[!0-9]*) echo '准备时间请填 0–120 秒，例如：sh collect-enco-x4.sh 60'; exit 2 ;;
esac
[ "${#PREPARE_SECONDS}" -le 3 ] && [ "$PREPARE_SECONDS" -le 120 ] || { echo '准备时间范围为 0–120 秒。'; exit 2; }
OUT_DIR=/sdcard/Download/Enco-LC3/X4
mkdir -p "$OUT_DIR" || exit 1
umask 077
TMP_DIR=$(mktemp -d /data/local/tmp/enco-x4-report.XXXXXX) || exit 1
LIVE_PID=
TIMER_PID=
cleanup() {
 [ -z "$LIVE_PID" ] || kill "$LIVE_PID" 2>/dev/null || true
 [ -z "$TIMER_PID" ] || kill "$TIMER_PID" 2>/dev/null || true
 rm -rf "$TMP_DIR"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
run_bounded() {
 seconds=$1
 shift
 if command -v timeout >/dev/null 2>&1; then
  timeout "$seconds" "$@"
 else
  "$@" &
  command_pid=$!
  (sleep "$seconds"; kill "$command_pid" 2>/dev/null || true) &
  guard_pid=$!
  wait "$command_pid"
  status=$?
  kill "$guard_pid" 2>/dev/null || true
  wait "$guard_pid" 2>/dev/null || true
  return "$status"
 fi
}
# Keep addresses for group/topology diagnosis, but omit lines that may carry keys.
sanitize() {
 grep -Eiv '(^|[^[:alnum:]])(sirk|ltk|irk|csrk|link[ _-]?key|long[ _-]?term[ _-]?key|identity[ _-]?resolving[ _-]?key|encryption[ _-]?key|key[ _-]?material|key[ _-]?value|key[ _-]?data)([^[:alnum:]]|$)' || true
}
REPORT="$OUT_DIR/enco-x4-$(date +%Y%m%d-%H%M%S)-$$.txt"
: > "$REPORT"
say() { printf '%s\n' "$*" | tee -a "$REPORT"; }
# Record immediately, including all preparation/reconnection operations.
logcat -b all -v threadtime -T 1 > "$TMP_DIR/live.txt" 2>&1 &
LIVE_PID=$!
say "开始记录：$(date)"
say "准备倒计时 $PREPARE_SECONDS 秒：请取出 Enco X4 双耳、正常连接并打开音乐。"
say '准备期间持续记录，连接瞬间也会保留。'
say '倒计时结束后再留 8 秒复现操作，随后读取状态约需 2–12 秒；全程不改蓝牙设置。'
remaining=$PREPARE_SECONDS
while [ "$remaining" -gt 0 ]; do
 if [ "$remaining" -le 5 ] || [ $((remaining % 10)) -eq 0 ]; then
  say "距额外测试窗口还有 $remaining 秒……"
 fi
 sleep 1
 remaining=$((remaining - 1))
done
say '额外测试窗口（8 秒）：现在可调音量、切换设置或复现连接问题。'
(sleep 8; kill "$LIVE_PID" 2>/dev/null || true) &
TIMER_PID=$!

{
 echo 'Enco X4 read-only capability report v1'
 date
 echo '=== Phone / ROM ==='
 for name in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.display.id ro.build.version.oplusrom ro.build.version.ota; do
  printf '%s=' "$name"
  getprop "$name"
 done
 echo '=== LE Audio properties (not modified) ==='
 getprop | grep -Ei 'bluetooth.*(leaudio|le_audio|csip|vcp|bap)|oplus.*(leaudio|le_audio)' || true
 echo '=== Companion app versions ==='
 for pkg in com.oplus.melody com.heytap.headset com.oplus.wirelesssettings local.enco.lc3; do
  echo "$pkg"
  run_bounded 2 dumpsys package "$pkg" 2>&1 | grep -E 'versionCode=|versionName=|Unable to find|not found' || true
 done
} > "$TMP_DIR/header.txt"
# Sampling stops after preparation + 8 seconds, independently of slow status reads.
wait "$TIMER_PID" 2>/dev/null || true
TIMER_PID=
wait "$LIVE_PID" 2>/dev/null || true
LIVE_PID=
cat "$TMP_DIR/header.txt" >> "$REPORT"
{
 echo '=== Bluetooth cached devices / UUIDs / profiles / groups ==='
 run_bounded 8 dumpsys bluetooth_manager 2>&1
 echo '=== Audio Bluetooth routing / volume ==='
 run_bounded 4 dumpsys audio 2>&1 | grep -Ei 'bluetooth|ble_|le.audio|a2dp|hearing|volume|mute|sco|device:|devices:|stream_music|stream_voice_call' | tail -220
 echo '=== Recent relevant Bluetooth logs (up to 2500 input lines) ==='
 run_bounded 3 logcat -b all -d -t 2500 -v threadtime 2>&1 | grep -Ei 'enco|lc3|le.?audio|csip|csis|pacs|ascs|bap|btif|bta_|bt_stack|bt_btif|bluetooth|spp.*(le|gatt)|volumecontrol|m_spp_le|m_bt_le' | tail -1200
 echo '=== Relevant live logs from startup through preparation + 8 seconds ==='
 grep -Ei 'enco|lc3|le.?audio|csip|csis|pacs|ascs|bap|btif|bta_|bt_stack|bt_btif|bluetooth|spp.*(le|gatt)|volumecontrol|m_spp_le|m_bt_le' "$TMP_DIR/live.txt"
 echo '=== End ==='
 date
} | sanitize >> "$REPORT"
say "Saved: $REPORT"
echo '把此 txt 发给模块维护者，并说明：手机型号、X4 固件、播放编码、单双耳出声及测试操作。'
echo '日志保留设备名称和蓝牙地址以诊断双耳关系；公开发帖前请隐去无关设备信息。'
echo '此脚本仅采集，未修改任何蓝牙设置。'
