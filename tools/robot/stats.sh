#!/system/bin/sh
# CPU and memory sampler for the robot, started by tools/robot_stats.py. It is its own process,
# outside Toque: the same measure works with any version of the app.
#
# Usage: sh stats.sh <output file> <interval seconds> <max samples>
#
# It only writes down what the kernel says (/proc, /sys); every computation is done on the PC
# (this shell counts on 32 bits). One `cat` per sample reads every process, so that the sampler
# stays cheap; it appears in its own measure.
#
# PROC and SYS change in tests only.
OUT=$1
INTERVAL=${2:-5}
MAX=${3:-4320}
PROC=${PROC:-/proc}
SYS=${SYS:-/sys}
[ -n "$OUT" ] || { echo "usage: stats.sh <output> [interval] [max samples]"; exit 1; }

# Android 8 (Smart) lists every process with -A only; Android 6 (Connect) takes -A for a name.
processes() {
  all=$(ps -A 2>/dev/null)
  case "$all" in
    *zygote*) echo "$all" ;;
    *) ps ;;
  esac
}

# Samples between two memory measures: about a minute, and never every sample.
EVERY=$((60 / INTERVAL))
[ $EVERY -ge 2 ] || EVERY=2

n=0
echo $$ > "$OUT.pid"
{
  echo "#toque-stats 1"
  echo "#interval $INTERVAL"
  echo "#model $(getprop ro.product.model 2>/dev/null)"
  echo "#android $(getprop ro.build.version.release 2>/dev/null)"
  # Cut by the shell: the robot's `sed` leaves this line as it is.
  v=$(dumpsys package app.toque 2>/dev/null | grep -m 1 versionName)
  echo "#toque ${v##*versionName=}"
  echo "#sampler $$"
} > "$OUT"

while [ $n -lt "$MAX" ] && [ ! -e "$OUT.stop" ]; do
  {
    read -r up idle < $PROC/uptime
    echo "@S $n $up"
    echo "@online $(cat $SYS/devices/system/cpu/online 2>/dev/null)"
    echo "@freq $(cat $SYS/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)"
    echo "@cpu"
    grep '^cpu' $PROC/stat
    echo "@mem"
    cat $PROC/meminfo
    echo "@proc"
    cat $PROC/[0-9]*/stat 2>/dev/null
    # Full names (the stat line only has 15 characters) and, once a minute, the memory share (PSS)
    # of Toque's processes. `dumpsys meminfo` makes Toque itself work: the report leaves the CPU of
    # the interval that follows out of Toque's figures.
    if [ $((n % EVERY)) -eq 0 ]; then
      list=$(processes)
      echo "@ps"
      echo "$list"
      for p in $(echo "$list" | grep ' app\.toque' | while read -r user pid rest; do echo "$pid"; done); do
        echo "@pss $p"
        dumpsys meminfo "$p" 2>/dev/null | grep -m 1 'TOTAL'
      done
    fi
    echo "@end"
  } >> "$OUT"
  n=$((n + 1))
  sleep "$INTERVAL"
done
rm -f "$OUT.pid" "$OUT.stop"
