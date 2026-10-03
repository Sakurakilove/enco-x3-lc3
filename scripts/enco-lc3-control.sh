#!/system/bin/sh
set -eu
[ "$(id -u)" = 0 ] || { echo '需要 Root：请授权模块应用，或在 su -c 中运行。' >&2; exit 1; }
ACTION=${1:-help}
command_file=
log_pid=
cleanup() {
 if [ -n "$log_pid" ]; then kill "$log_pid" 2>/dev/null || true; wait "$log_pid" 2>/dev/null || true; fi
 [ -z "$command_file" ] || rm -f "$command_file"
}
trap cleanup EXIT
trap 'echo "流程被停止，正在关闭日志记录。"; exit 130' HUP INT TERM
new_command_file() {
 if [ -z "$command_file" ]; then
  umask 077
  command_file=$(mktemp /data/local/tmp/enco-lc3-command.XXXXXX)
 fi
}
restart_bluetooth() {
 echo '正在重启蓝牙进程，已连接的蓝牙设备会暂时断开。'
 timeout 10 cmd bluetooth_manager disable
 sleep 3
 for pid in $(pidof com.android.bluetooth || true); do
  name=$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | head -n 1)
  [ "$name" != com.android.bluetooth ] || kill -TERM "$pid" || true
 done
 sleep 2
 timeout 10 cmd bluetooth_manager enable
 sleep 4
}
send_action() {
 new_command_file
 timeout 6 am broadcast --receiver-foreground -a "local.enco.lc3.$1" -p com.android.bluetooth > "$command_file" 2>&1
 cat "$command_file"
 grep -q 'result=1' "$command_file" || { echo '模块未确认操作成功：检查 LSPosed 的蓝牙作用域和上方原因。' >&2; exit 3; }
}
configure_and_start() {
 ADDRESS=${1:-auto}
 if [ "$ADDRESS" != auto ]; then
  printf '%s\n' "$ADDRESS" | grep -Eq '^([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}$' || { echo '主地址格式不正确。' >&2; exit 2; }
 fi
 timeout 8 dumpsys package local.enco.lc3 | grep -q 'versionCode=19 ' || { echo '请先安装 v0.19，并在 LSPosed 启用模块、勾选蓝牙。' >&2; exit 2; }
 restart_bluetooth
 new_command_file
 echo '正在识别已配对的 Enco X3，并保存主耳机地址。'
 timeout 6 am broadcast --receiver-foreground -a local.enco.lc3.CONFIGURE_TARGET -p com.android.bluetooth --es address "$ADDRESS" > "$command_file" 2>&1
 cat "$command_file"
 if ! grep -q 'result=1' "$command_file"; then
  if grep -Eq 'Multiple paired|No unique paired' "$command_file"; then
   echo 'NEEDS_ADDRESS: 无法唯一识别，请普通连接一次后重试，或填写主地址。'
  fi
  echo '配置失败，查看上方原因；切换目标前先断开旧耳机。' >&2
  exit 3
 fi
 send_action START_LE
 echo '已请求 LC3 连接，保持双耳盒外。'
}
case "$ACTION" in
 setup)
  # A process group deadline also bounds the logcat child and native commands.
  exec timeout -s TERM -k 3 90 sh "$0" _setup "${2:-auto}"
  ;;
 _setup)
  echo '开始一键配置。实时记录已开启，完成、失败或超时后自动停止。'
  logcat -T 1 -b all -v threadtime -s EncoLc3Probe:V VolumeControlService:I VolumeControlStateMachine:I CsipSetCoordinatorService:I BatteryService:I McpService:I TbsService:I '*:S' &
  log_pid=$!
  configure_and_start "${2:-auto}"
  echo '正在等待双耳 LE、分组、音量及原生控制服务，最多检查 30 秒。'
  for attempt in $(seq 1 15); do
   sleep 2
   timeout 6 am broadcast --receiver-foreground -a local.enco.lc3.CHECK_CONTROLS -p com.android.bluetooth > "$command_file" 2>&1
   if grep -q 'result=1' "$command_file"; then
    cat "$command_file"
    echo '完成：双耳 LE、CSIP、VCP 及可用原生控制服务已就绪。请播放音乐确认实际声音。'
    exit 0
   fi
   if ! grep -q 'result=0' "$command_file"; then
    cat "$command_file"
    echo '状态查询未成功，已停止本次流程。' >&2
    exit 3
   fi
   cat "$command_file"
  done
  cat "$command_file"
  echo '等待结束：尚未确认双耳全部就绪，本次日志记录已停止。连接恢复任务可能仍在进行。' >&2
  exit 3
  ;;
 configure) configure_and_start "${2:-auto}" ;;
 start) send_action START_LE ;;
 repair-controls) send_action REPAIR_CONTROLS ;;
 restore) send_action RESTORE_CLASSIC ;;
 restart) restart_bluetooth ;;
 collect)
  echo '=== Module version ==='
  dumpsys package local.enco.lc3 | grep -E 'versionCode=|versionName=|enabled=' || true
  echo '=== Recent module logs ==='
  logcat -b all -d -v threadtime -s EncoLc3Probe:V '*:S' | tail -300
  echo '=== Bluetooth profiles and native audio state ==='
  dumpsys bluetooth_manager
  ;;
 *) echo '用法：enco-lc3-control.sh setup [auto 或主地址] | configure [auto 或主地址] | start | repair-controls | restore | restart | collect' ;;
esac
