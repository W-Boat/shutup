#!/system/bin/sh
# Fixed, bundled script. Paths/package/user are supplied by the application, never AI or a server.
enabled=__ENABLED__
heartbeat=__HEARTBEAT__
runtime=__RUNTIME__
package=__PACKAGE__
user=__USER__
service=__SERVICE__
mkdir "$runtime/running" 2>/dev/null || exit 0
echo $$ > "$runtime/pid"
trap 'rm -f "$runtime/pid"; rmdir "$runtime/running" 2>/dev/null' EXIT
trap 'exit 0' TERM INT HUP
attempts=0
echo watching > "$runtime/status"
while [ -f "$enabled" ]; do
    stamp=$(cat "$heartbeat" 2>/dev/null)
    case "$stamp" in ''|*[!0-9]*) stamp=0 ;; esac
    now=$(date +%s)
    age=$((now - stamp))
    if [ "$age" -gt 45 ] || [ "$age" -lt 0 ]; then
        if [ "$attempts" -ge 3 ]; then
            echo failed > "$runtime/status"
            exit 1
        fi
        [ -f "$enabled" ] || break
        # unstop is available on newer releases. A missing command is not treated as success.
        timeout 8 cmd package unstop --user "$user" "$package" > "$runtime/last-result" 2>&1
        [ -f "$enabled" ] || break
        timeout 8 am start-foreground-service --user "$user" -n "$service" -a reconcile >> "$runtime/last-result" 2>&1
        result=$?
        echo "restart:$result" > "$runtime/status"
        attempts=$((attempts + 1))
        sleep 30
    else
        attempts=0
        echo watching > "$runtime/status"
        sleep 15
    fi
done
echo stopped > "$runtime/status"
