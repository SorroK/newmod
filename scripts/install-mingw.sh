#!/usr/bin/env bash
# Installs a mingw-w64 toolchain under $HOME so the Rust core can be
# cross-compiled to 64-bit Windows from Linux.
#
# Why not `apt-get install`: the cloud sandbox has no root (sudo is blocked by
# no_new_privs). The .debs are unpacked with dpkg-deb into a private prefix,
# which needs no privileges and touches nothing in /usr.
#
# The archive prefix is not preserved when the workspace is archived, so re-run
# this after a restore. It is idempotent.
#
# Usage: scripts/install-mingw.sh
set -euo pipefail

PREFIX="${MINGW_PREFIX:-$HOME/tools/mingw}"
WORK="${MINGW_WORK:-$HOME/tools/mingw-debs}"
GCC="$PREFIX/usr/bin/x86_64-w64-mingw32-gcc"

if [ -x "$GCC" ]; then
    echo "mingw-w64 already installed at $PREFIX"
    exit 0
fi

mkdir -p "$WORK" "$PREFIX"
cd "$WORK"

# mingw-w64 lives in Ubuntu's universe component.
echo "fetching package index..."
curl -sfLo Packages.gz http://archive.ubuntu.com/ubuntu/dists/noble/universe/binary-amd64/Packages.gz
gunzip -f Packages.gz

PACKAGES="binutils-mingw-w64-x86-64 mingw-w64-common mingw-w64-x86-64-dev gcc-mingw-w64-base gcc-mingw-w64-x86-64-posix"

for package in $PACKAGES; do
    filename=$(awk -v pkg="$package" '
        BEGIN { RS = "" }
        {
            name = ""; file = ""
            n = split($0, lines, "\n")
            for (i = 1; i <= n; i++) {
                if (lines[i] ~ /^Package: /)  name = substr(lines[i], 10)
                if (lines[i] ~ /^Filename: /) file = substr(lines[i], 11)
            }
            if (name == pkg) { print file; exit }
        }' Packages)

    if [ -z "$filename" ]; then
        echo "error: $package not found in the noble universe index" >&2
        exit 1
    fi

    echo "fetching $(basename "$filename")"
    curl -sfLO "http://archive.ubuntu.com/ubuntu/$filename"
    dpkg-deb -x "$(basename "$filename")" "$PREFIX"
done

# gcc-mingw-w64-x86-64-posix ships only the suffixed driver; rustc wants the
# plain name.
if [ ! -x "$GCC" ]; then
    ln -sf "$PREFIX/usr/bin/x86_64-w64-mingw32-gcc-posix" "$GCC"
fi

"$GCC" --version | head -1
echo "OK: mingw-w64 installed at $PREFIX"
