user=__USER__
target=__COMPONENT__
current=$(settings --user "$user" get secure enabled_accessibility_services) || exit 1
[ "$current" != null ] || current=''
# Rebinding a stale service requires an actual change. Remove only our exact component,
# preserving every other service, then read again before appending it back.
case ":$current:" in
    *":$target:"*)
        remaining=''
        oldifs=$IFS
        IFS=:
        set -f
        for entry in $current; do
            [ "$entry" = "$target" ] && continue
            [ -n "$entry" ] || continue
            if [ -n "$remaining" ]; then remaining="$remaining:$entry"; else remaining="$entry"; fi
        done
        set +f
        IFS=$oldifs
        settings --user "$user" put secure enabled_accessibility_services "$remaining" || exit 1
        current=$(settings --user "$user" get secure enabled_accessibility_services) || exit 1
        [ "$current" != null ] || current=''
        ;;
esac
case ":$current:" in
    *":$target:"*) ;;
    *) if [ -n "$current" ]; then current="$current:$target"; else current="$target"; fi ;;
esac
settings --user "$user" put secure enabled_accessibility_services "$current" &&
    settings --user "$user" put secure accessibility_enabled 1
