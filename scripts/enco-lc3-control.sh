#!/system/bin/sh
set -eu
[ "$(id -u)" = 0 ] || { echo '请在 su -c 中运行此脚本。' >&2; exit 1; }
ACTION=${1:-help}
restart_bluetooth() {
 cmd bluetooth_manager disable
 sleep 3
 for pid in $(pidof com.android.bluetooth || true); do
  name=$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | head -n 1)
  [ "$name" != com.android.bluetooth ] || kill -TERM "$pid" || true
 done
 sleep 2
 cmd bluetooth_manager enable
 sleep 4
}
command_file=
cleanup() { [ -z "$command_file" ] || rm -f "$command_file"; }
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
send_action() {
 if [ -z "$command_file" ]; then
  umask 077
  command_file=$(mktemp /data/local/tmp/enco-lc3-command.XXXXXX)
 fi
 am broadcast --receiver-foreground -a "local.enco.lc3.$1" -p com.android.bluetooth > "$command_file" 2>&1
 cat "$command_file"
 grep -q 'result=1' "$command_file" || { echo '模块未确认操作成功：检查 LSPosed 作用域、蓝牙进程和输出中的原因。' >&2; exit 3; }
}
case "$ACTION" in
 configure)
  ADDRESS=${2:-auto}
  if [ "$ADDRESS" != auto ]; then
   printf '%s\n' "$ADDRESS" | grep -Eq '^([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}$' || { echo '用法：configure [auto 或已配对耳机的主地址]' >&2; exit 2; }
  fi
  dumpsys package local.enco.lc3 | grep -q 'versionCode=15 ' || { echo '请先安装 v0.15 公开预览版，并在 LSPosed 勾选蓝牙。' >&2; exit 2; }
  restart_bluetooth
  umask 077
  command_file=$(mktemp /data/local/tmp/enco-lc3-command.XXXXXX)
  am broadcast --receiver-foreground -a local.enco.lc3.CONFIGURE_TARGET -p com.android.bluetooth --es address "$ADDRESS" > "$command_file" 2>&1
  cat "$command_file"
  grep -q 'result=1' "$command_file" || { echo '配置失败：查看上方原因；无法自动识别时手动填写已配对主地址，切换目标前先断开旧耳机。' >&2; exit 3; }
  send_action START_LE
  echo '配置已保存，LC3 连接请求已排队；保持双耳盒外，实际成功以声音和 LE Audio 日志为准。'
  ;;
 start) send_action START_LE ;;
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
 *) echo '用法：enco-lc3-control.sh configure [auto 或耳机主地址] | start | restore | restart | collect' ;;
esac
