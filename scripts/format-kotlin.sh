#!/usr/bin/env bash
set -euo pipefail

# Pin the formatter and verify the Maven Central artifact before executing it.
formatter_version="0.64"
formatter_checksum="5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2"
formatter_cache="${KTFMT_CACHE_DIR:-${TMPDIR:-/tmp}/legado-ktfmt}"
formatter_jar="$formatter_cache/ktfmt-$formatter_version.jar"
formatter_url="https://repo.maven.apache.org/maven2/com/facebook/ktfmt/$formatter_version/ktfmt-$formatter_version-with-dependencies.jar"

formatter_options=()
if [[ "${1:-}" == "--check" ]]; then
    formatter_options+=(--dry-run --set-exit-if-changed)
    shift
fi
if [[ $# -eq 0 ]]; then
    echo "Usage: scripts/format-kotlin.sh [--check] <file.kt> [file.kts ...]" >&2
    exit 2
fi

verify_formatter() {
    [[ -f "$1" ]] && [[ "$(shasum -a 256 "$1" | cut -d ' ' -f 1)" == "$formatter_checksum" ]]
}

if ! verify_formatter "$formatter_jar"; then
    mkdir -p "$formatter_cache"
    download_file="$(mktemp "$formatter_cache/download.XXXXXX")"
    trap 'rm -f "$download_file"' EXIT
    curl --fail --location --silent --show-error --retry 2 "$formatter_url" -o "$download_file"
    if ! verify_formatter "$download_file"; then
        echo "Formatter checksum did not match ktfmt $formatter_version." >&2
        exit 1
    fi
    mv "$download_file" "$formatter_jar"
    trap - EXIT
fi

exec java -jar "$formatter_jar" --kotlinlang-style --enable-editorconfig "${formatter_options[@]}" "$@"
