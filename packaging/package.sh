#!/usr/bin/env bash
# Builds a native installer that bundles its own Java runtime (users don't need Java).
#
#   packaging/package.sh <msi|exe|dmg|pkg|deb|rpm|app-image> [version]
#
# Run on the target OS (jpackage can't cross-build). Needs JDK 21+ on PATH/JAVA_HOME and the app jar in
# target/ (./mvnw package). Windows .msi/.exe also needs WiX Toolset 3.x on PATH.
#
# APP_IMAGE=<folder> packages an app image made earlier with `package.sh app-image` instead of the jar. The
# release build uses it on Windows to code-sign Creator CRM.exe before it goes into the .msi.
set -euo pipefail

TYPE="${1:?usage: package.sh <msi|exe|dmg|pkg|deb|rpm|app-image> [version]}"
cd "$(dirname "$0")/.."

# Default: the project's own <version> (the first one after the <parent> block in pom.xml).
VERSION="${2:-$(awk '/<\/parent>/{p=1; next} p && /<version>/{gsub(/.*<version>|<\/version>.*/, ""); print; exit}' pom.xml)}"
VERSION="${VERSION#v}"
VERSION="${VERSION%%-*}"   # installers need plain numbers: 1.2.3

JPACKAGE="jpackage"
if [ -n "${JAVA_HOME:-}" ]; then JPACKAGE="$JAVA_HOME/bin/jpackage"; fi

if [ -n "${APP_IMAGE:-}" ]; then
  # Copied out first: target/installer is cleared below, and an earlier app-image build lives there.
  rm -rf target/app-image-input && mkdir -p target/app-image-input && cp -R "$APP_IMAGE" target/app-image-input/
  SOURCE=(--app-image "target/app-image-input/$(basename "$APP_IMAGE")")
else
  JAR="$(ls -t target/creator-manager-*.jar | grep -v '\.original$' | head -1)"   # newest build
  rm -rf target/jpackage-input
  mkdir -p target/jpackage-input
  cp "$JAR" target/jpackage-input/creator-crm.jar
  SOURCE=(--input target/jpackage-input --main-jar creator-crm.jar
    --java-options "-Dcrm.desktop=true" --java-options "-Xmx768m")
fi
rm -rf target/installer
mkdir -p target/installer

COMMON=(
  "${SOURCE[@]}"
  --name "Creator CRM"
  --app-version "$VERSION"
  --vendor "Creator CRM contributors"
  --description "Brand-collaboration manager for content creators"
  --copyright "MIT License"
  --dest target/installer
)

case "$TYPE" in
  msi|exe)
    EXTRA=(
      --icon packaging/icons/creator-crm.ico
      --license-file LICENSE
      --win-menu --win-menu-group "Creator CRM"
      --win-shortcut
      --win-per-user-install      # installs for the current user only: no admin rights needed
      --win-dir-chooser
      --win-upgrade-uuid 698fa96a-5547-4768-9d3c-6b48eb86df8a   # never change: lets new versions upgrade old ones
    ) ;;
  dmg|pkg)
    EXTRA=(
      --icon packaging/icons/creator-crm.icns
      --license-file LICENSE
      --mac-package-identifier io.github.creatorcrm.app
      --mac-package-name "Creator CRM"
    ) ;;
  deb|rpm)
    EXTRA=(
      --icon packaging/icons/creator-crm.png
      --linux-package-name creator-crm
      --linux-shortcut
      --linux-menu-group Office
      --linux-app-category office
    ) ;;
  app-image)
    EXTRA=(--icon "packaging/icons/creator-crm.$( [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && echo ico || ([[ "$(uname -s)" == Darwin ]] && echo icns || echo png) )") ;;
  *) echo "Unknown type: $TYPE" >&2; exit 2 ;;
esac

echo "Building $TYPE for Creator CRM $VERSION ..."
"$JPACKAGE" --type "$TYPE" "${COMMON[@]}" "${EXTRA[@]}"
ls -la target/installer
