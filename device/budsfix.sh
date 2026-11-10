#!/system/bin/sh
# Buds Battery Fix: on-device supervisor. Runs as the adb shell user, so it needs neither the Linux
# Terminal nor an open app. The Buds Battery Fix app installs it into /data/local/tmp/budsfix and
# starts it through its own paired adb key at every restart and every 15 minutes.
#
#   sh budsfix.sh start     start (does nothing if it is already running); clears a stop
#   sh budsfix.sh ensure    the app's automatic start: does nothing if you stopped it
#   sh budsfix.sh restart   the app's restart after an app update (not if you stopped it)
#   sh budsfix.sh stop      stop; it stays stopped until "start"
#   sh budsfix.sh status    first line "running" or "NOT running", then details
#
# The helper's code always comes from the installed Buds Battery Fix app. It holds no wake lock and
# changes no setting; it only corrects the earbuds' stored battery values in one exact situation
# (see Helper.kt). Nothing is ever deleted: each month gets its own log file.

D=/data/local/tmp/budsfix
SCRIPT=$D/budsfix.sh
PKG=local.budsfix
HLP_CLASS=local.budsfix.Helper
umask 022
mkdir -p $D

note() { echo "$(date '+%Y-%m-%d %H:%M:%S %Z') supervisor: $*" >> $D/fix-$(date +%Y-%m).log; }
control() { cat $D/control 2>/dev/null; }

SUP="sh $SCRIPT supervise"
pids_of() { ps -A -o PID,ARGS 2>/dev/null | sed 1d | while read -r p a; do [ "$a" = "$1" ] && echo "$p"; done; }
pids_starting() { ps -A -o PID,ARGS 2>/dev/null | sed 1d | while read -r p a; do case "$a" in "$1"*) echo "$p" ;; esac; done; }
supervisor_alive() { [ -n "$(pids_of "$SUP")" ]; }
# The helper: a process whose command line STARTS with app_process and names our helper class
# (never a "contains" match on the whole line, which could hit an unrelated command).
helper_pids() {
    ps -A -o PID,ARGS 2>/dev/null | sed 1d | while read -r p a; do
        case "$a" in app_process*|/system/bin/app_process*) case "$a" in *"$HLP_CLASS"*) echo "$p" ;; esac ;; esac
    done
}

# The installed app's code (empty if the package manager has no answer right now).
apk_path() {
    for u in current 0; do
        a=$(timeout 10 pm path --user $u $PKG 2>/dev/null | head -1 | cut -d: -f2)
        [ -n "$a" ] && [ -r "$a" ] && { echo "$a"; return; }
    done
}

supervise() {
    echo $$ > $D/supervisor.pid
    note "started (pid $$, build $(getprop ro.build.fingerprint))"
    fails=0
    while [ "$(control)" != stop ]; do
        [ "$(cat $D/supervisor.pid 2>/dev/null)" = "$$" ] || exit 0
        for p in $(helper_pids); do kill $p 2>/dev/null; done   # never two helpers
        APK=$(apk_path)
        if [ -z "$APK" ]; then
            note "the app's code is not available right now; trying again in 30 s"
            sleep 30
            continue
        fi
        t0=$(date +%s)
        app_process -cp "$APK" / $HLP_CLASS >> $D/fix-$(date +%Y-%m).log 2>&1 9>&- &
        echo $! > $D/helper.pid
        wait $!
        rc=$?
        [ "$(control)" = stop ] && break
        if [ $(( $(date +%s) - t0 )) -lt 60 ]; then fails=$((fails + 1)); else fails=0; fi
        delay=5; [ $fails -ge 3 ] && delay=60; [ $fails -ge 6 ] && delay=600
        note "helper exited (code $rc); starting it again in $delay s"
        sleep $delay
    done
    note "stopped"
}

locked() {
    (
        i=0
        until flock -n 9; do
            i=$((i + 1)); [ $i -ge 30 ] && { echo "another start or stop is still in progress"; exit 0; }
            sleep 1
        done
        "$@"
    ) 9>>$D/control.lock
}
do_start() {   # $1 = start | ensure
    [ "$1" = ensure ] && [ "$(control)" = stop ] && { echo "stopped by you"; return; }
    [ "$1" = start ] && echo run > $D/control
    supervisor_alive && { echo "already running"; return; }
    setsid sh $SCRIPT supervise < /dev/null > /dev/null 2>&1 9>&- &
    i=0
    while ! supervisor_alive && [ $i -lt 5 ]; do sleep 1; i=$((i + 1)); done
    if supervisor_alive; then echo "started"; else echo "failed to start"; fi
}
stop_all() {
    for p in $(pids_of "$SUP"); do kill $p 2>/dev/null; done
    for p in $(helper_pids); do kill $p 2>/dev/null; done
    i=0
    while [ -n "$(pids_of "$SUP")$(helper_pids)" ] && [ $i -lt 5 ]; do sleep 1; i=$((i + 1)); done
    for p in $(pids_of "$SUP") $(helper_pids); do kill -9 $p 2>/dev/null; done
}
do_restart() {
    [ "$(control)" = stop ] && { echo "stopped by you"; return; }
    stop_all
    note "restarting with the newly installed app"
    do_start ensure
}
do_stop() {
    echo stop > $D/control
    stop_all
    note "stopped by request"
    echo "stopped"
}

case "$1" in
    start|ensure) locked do_start "$1" ;;
    restart)      locked do_restart ;;
    stop)         locked do_stop ;;
    supervise)    supervise ;;
    status)
        if supervisor_alive && [ -n "$(helper_pids)" ]; then echo "running"
        elif supervisor_alive; then echo "NOT running: the supervisor is waiting to start the helper"
        else echo "NOT running"; fi
        [ "$(control)" = stop ] && echo "stopped by you"
        n=$(grep -c ' fixed: ' $D/fix-$(date +%Y-%m).log 2>/dev/null)
        echo "fixes this month: ${n:-0}"
        echo "last fix: $(grep ' fixed: ' $D/fix-$(date +%Y-%m).log 2>/dev/null | tail -1 | cut -c1-19)"
        echo "--- log ---"
        tail -n "${2:-8}" $D/fix-$(date +%Y-%m).log 2>/dev/null
        ;;
    *) echo "usage: sh budsfix.sh start|ensure|restart|stop|status"; exit 2 ;;
esac
