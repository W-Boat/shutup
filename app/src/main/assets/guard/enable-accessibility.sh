user=__USER__
target=__COMPONENT__
current=$(settings --user "$user" get secure enabled_accessibility_services) || exit 1
[ "$current" != null ] || current=''
case ":$current:" in
    *":$target:"*) ;;
    *) if [ -n "$current" ]; then current="$current:$target"; else current="$target"; fi ;;
esac
settings --user "$user" put secure enabled_accessibility_services "$current" &&
    settings --user "$user" put secure accessibility_enabled 1
