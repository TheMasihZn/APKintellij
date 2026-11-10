#!/usr/bin/env bash
# Builds the Linux image that ships inside the APK:
#   Debian (arm64) + Xvnc + openbox + noVNC + IntelliJ IDEA (Linux ARM64 build, with its own JBR)
# and writes app/src/main/assets/rootfs.tar.xz and rootfs.version.
#
# Needs Docker with arm64 emulation (docker/setup-qemu-action on CI, or
# `docker run --privileged --rm tonistiigi/binfmt --install arm64` locally), curl, jq, xz.
#
# IDEA_URL   use this IntelliJ Linux ARM64 .tar.gz instead of the latest release
# IDEA_CODE  JetBrains product code to look up when IDEA_URL is empty (default IIC, then IIU)
set -euo pipefail
cd "$(dirname "$0")/.."

ASSETS=app/src/main/assets
WORK=work/rootfs
NAME=apkintellij-rootfs-$$
mkdir -p "$ASSETS" "$WORK"

PACKAGES=(
  tigervnc-standalone-server openbox novnc websockify python3-numpy
  xterm x11-xserver-utils fontconfig fonts-dejavu-core fonts-jetbrains-mono
  libxtst6 libxi6 libxrender1 libxrandr2 libfreetype6 libasound2
  git openssh-client ca-certificates curl wget unzip procps less nano
)

if [ -z "${IDEA_URL:-}" ]; then
  for code in ${IDEA_CODE:-IIC IIU}; do
    IDEA_URL=$(curl -fsSL "https://data.services.jetbrains.com/products/releases?code=$code&latest=true&type=release" \
      | jq -r --arg c "$code" '.[$c][0].downloads.linuxARM64.link // empty')
    [ -n "$IDEA_URL" ] && break
  done
fi
[ -n "${IDEA_URL:-}" ] || { echo "could not find an IntelliJ Linux ARM64 download" >&2; exit 1; }
echo "IntelliJ: $IDEA_URL"
curl -fL --retry 3 "$IDEA_URL" -o "$WORK/idea.tar.gz"
rm -rf "$WORK/idea" && mkdir -p "$WORK/idea"
tar -xzf "$WORK/idea.tar.gz" -C "$WORK/idea" --strip-components=1
rm "$WORK/idea.tar.gz"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker create --platform linux/arm64 --name "$NAME" debian:bookworm sleep infinity
docker start "$NAME"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

docker exec -e DEBIAN_FRONTEND=noninteractive "$NAME" bash -euc "
  apt-get update
  apt-get install -y --no-install-recommends ${PACKAGES[*]}
  apt-get clean
  rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/*
  # Maximize IntelliJ windows without a frame, so it looks like a native DeX app.
  sed -i 's|<applications>|<applications><application class=\"jetbrains-*\" type=\"normal\"><decor>no</decor><maximized>true</maximized></application>|' /etc/xdg/openbox/rc.xml
"
docker cp "$WORK/idea" "$NAME:/opt/idea"
tar -C rootfs-overlay -cf - . | docker exec -i "$NAME" tar -C / --no-same-owner -xf -
docker exec "$NAME" bash -euc "chmod 755 /opt/apkintellij/start.sh; test -x /opt/idea/bin/idea.sh; test -d /opt/idea/jbr"
docker stop -t 1 "$NAME"

echo "exporting and compressing the rootfs (this takes a few minutes)"
docker export "$NAME" | xz -T0 -6 > "$ASSETS/rootfs.tar.xz"
version="$(date -u +%Y%m%d%H%M%S)-$(basename "$IDEA_URL" .tar.gz)"
echo "$version" > "$ASSETS/rootfs.version"
rm -rf "$WORK/idea"
ls -lh "$ASSETS"
echo "rootfs version: $version"
