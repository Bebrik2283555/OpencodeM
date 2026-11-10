#!/bin/sh
# Builds the release apk and signs it with MargyCode's own key (keys/, kept out of git).
# Phone makers' scanners treat apks signed with public debug keys as viruses.
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${JAVA_HOME:=$HOME/.zcode/android-build/jdk-17.0.20.1+1}"
export JAVA_HOME
gradle=${GRADLE:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle | head -1)}
cd "$here"
"$gradle" -q :app:assembleRelease
version=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
mkdir -p out
apk="out/OpencodeM-$version.apk"
cp app/build/outputs/apk/release/app-release-unsigned.apk "$apk"
pass=$(cat keys/margycode.pass)
java -jar "${SIGNER:-$HOME/MargyT/tools/uber-apk-signer.jar}" -a "$apk" --allowResign --overwrite \
    --ks keys/margycode.jks --ksAlias margycode --ksPass "$pass" --ksKeyPass "$pass" > /dev/null
echo "$here/$apk"
