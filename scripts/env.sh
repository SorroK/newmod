#!/usr/bin/env bash
# Repeatable toolchain setup for the cloud/CI environment.
#
# The cloud sandbox has no JDK, Rust or Gradle, and no root (sudo is blocked by
# no_new_privs), so everything is installed under $HOME. System installs are not
# preserved when the workspace is archived; re-run this after a restore.
#
# Usage: source scripts/env.sh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk21}"
export GRADLE_HOME="${GRADLE_HOME:-$HOME/tools/gradle-9.8.2}"
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$CARGO_HOME/bin:$PATH"

# mingw-w64 (optional) enables the Windows cross-build. Install it with
# scripts/install-mingw.sh; Gradle finds it on its own, but exporting the
# linker also makes a bare `cargo build --target x86_64-pc-windows-gnu` work.
if [ -x "$HOME/tools/mingw/usr/bin/x86_64-w64-mingw32-gcc" ]; then
    export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER="$HOME/tools/mingw/usr/bin/x86_64-w64-mingw32-gcc"
    export PATH="$HOME/tools/mingw/usr/bin:$PATH"
fi
