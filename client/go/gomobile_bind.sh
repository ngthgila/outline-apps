#!/usr/bin/env bash
set -euo pipefail

bin_dir="$1"
shift

if command -v cygpath >/dev/null 2>&1; then
  bin_dir="$(cygpath -u "$bin_dir")"
fi

export PATH="${bin_dir}:${PATH}"
export CGO_CFLAGS="-fstack-protector-strong"

exec "${bin_dir}/gomobile" bind -ldflags="-s -w" "$@"
