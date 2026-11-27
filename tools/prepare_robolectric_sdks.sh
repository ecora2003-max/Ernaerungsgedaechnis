#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    printf 'Usage: %s <sdk-directory>\n' "$0" >&2
    exit 2
fi

directory=$1
mkdir -p -- "$directory"
download=$(mktemp -- "$directory/.robolectric-artifact.XXXXXXXX")
trap 'rm -f -- "$download"' EXIT

# These are the Android SDK builds used by Robolectric 4.14.1. Maven Central
# and its published Google-hosted mirror must deliver the same pinned bytes.
verify() {
    local digest=$1 file=$2
    [[ -f "$file" && $(sha256sum < "$file") == "$digest  -" ]]
}

fetch() {
    local artifact=$1 destination=$2 digest=$3
    if verify "$digest" "$destination"; then
        return
    fi
    mkdir -p -- "$(dirname -- "$destination")"
    local repository
    for repository in https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
        if curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 10 --max-time 60 --retry 1 --retry-max-time 65 \
                --output "$download" "$repository/$artifact" && verify "$digest" "$download"; then
            mv -f -- "$download" "$destination"
            return
        fi
    done
    printf 'Could not provision the verified Robolectric artifact %s\n' "$artifact" >&2
    return 1
}

while read -r version digest; do
    jar="android-all-instrumented-$version.jar"
    fetch "org/robolectric/android-all-instrumented/$version/$jar" "$directory/$jar" "$digest"
    printf 'Verified Robolectric SDK %s\n' "$version"
done <<'SDK_ARTIFACTS'
13-robolectric-9030017-i7 1c3fd182c03d47626686ca3b39c4ec5bdb0898b1056f4eb8f96f32c6209a5c04
14-robolectric-10818077-i7 3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701
15-robolectric-12650502-i7 33c2d7cc4186d4605269d5d411fa1818972c5b30a9f70bfbbd3d430b076af52f
SDK_ARTIFACTS

# Gradle resolves this separate native distribution for Robolectric's test
# runtime. Limit the local Maven layout to these exact pinned coordinates.
native="org/robolectric/nativeruntime-dist-compat/1.0.16/nativeruntime-dist-compat-1.0.16"
fetch "$native.pom" "$directory/maven/$native.pom" 053dc5be36c03495d157186588b73952f4098cb9890f75cbd6ee26e36fd83d04
fetch "$native.jar" "$directory/maven/$native.jar" 2f4e879b00eed634d0e43353ecff80db4d5ce24b3b213d1e6053cb21b0ced10f
printf 'Verified Robolectric native test runtime 1.0.16\n'
