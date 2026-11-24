#!/bin/sh
# netspeed installer — downloads the latest APK
set -e

REPO="netspeed"
log()  { printf '\033[36m[netspeed]\033[0m %s\n' "$1"; }
die()  { printf '\033[31m[netspeed]\033[0m %s\n' "$1" >&2; exit 1; }

log "fetching the latest release"
URL="https://github.com/xqe-bkflda/$REPO/releases/latest/download/netspeed-v1.2.apk"
OUT="${1:-$HOME/Download/netspeed.apk}"

if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$URL" -o "$OUT" || die "download failed"
else
  wget -qO "$OUT" "$URL" || die "download failed"
fi

log "saved to $OUT"
log "install it with:  adb install -r $OUT"
log "or copy the file to the phone and open it there"

cat <<'EOF'

  Done.

  After installing, grant the "Display over other apps" permission on first launch.

  Docs: https://github.com/xqe-bkflda/netspeed#readme

EOF
