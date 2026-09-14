#!/bin/bash
# SessionStart hook for Claude Code on the web.
#
# Installs the standalone cljfmt and clj-kondo binaries used by CI
# (`.github/workflows/ci.yml`, steps "Lint (clj-kondo)" and
# "Format check (cljfmt)") so agents working in the cloud sandbox can
# run the same pre-push checks. Necessary because the deps.edn :lint
# and :fmt aliases fetch their tools from Clojars, which the cloud
# sandbox cannot reach (HTTP 403). github.com release downloads are
# allowed, so we use the standalone distributions from there.
#
# Pinned versions match the deps.edn :lint and :fmt aliases. Bump them
# in lockstep if you change one.
#
# Idempotent: skips download when the expected binary at the expected
# version is already present.

set -euo pipefail

# Local dev (outside the sandbox) keeps using `clojure -M:lint` and
# `clojure -M:fmt`; this hook is a workaround for the remote env only.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

CLJFMT_VERSION="0.13.0"
CLJKONDO_VERSION="2024.11.14"

BIN_DIR="${CLAUDE_PROJECT_DIR:-$PWD}/.claude/bin"
mkdir -p "$BIN_DIR"

have_version() {
  # $1: binary path, $2: expected exact version-line output
  [ -x "$1" ] && [ "$("$1" --version 2>/dev/null)" = "$2" ]
}

install_cljfmt() {
  local target="$BIN_DIR/cljfmt"
  local expected="cljfmt $CLJFMT_VERSION"
  if have_version "$target" "$expected"; then
    return 0
  fi
  local url="https://github.com/weavejester/cljfmt/releases/download/${CLJFMT_VERSION}/cljfmt-${CLJFMT_VERSION}-linux-amd64.tar.gz"
  local tarball
  tarball="$(mktemp)"
  curl -fsSL --connect-timeout 15 --retry 2 -o "$tarball" "$url"
  tar -xzf "$tarball" -C "$BIN_DIR" cljfmt
  rm -f "$tarball"
  chmod +x "$target"
}

install_clj_kondo() {
  local target="$BIN_DIR/clj-kondo"
  local expected="clj-kondo v$CLJKONDO_VERSION"
  if have_version "$target" "$expected"; then
    return 0
  fi
  local url="https://github.com/clj-kondo/clj-kondo/releases/download/v${CLJKONDO_VERSION}/clj-kondo-${CLJKONDO_VERSION}-linux-amd64.zip"
  local zip
  zip="$(mktemp --suffix=.zip)"
  curl -fsSL --connect-timeout 15 --retry 2 -o "$zip" "$url"
  unzip -o -q "$zip" -d "$BIN_DIR"
  rm -f "$zip"
  chmod +x "$target"
}

install_cljfmt
install_clj_kondo

# Persist PATH for the rest of the session so subsequent shells (and
# Bash tool invocations) can find cljfmt and clj-kondo by name.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export PATH=\"$BIN_DIR:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi

# Banner — confirms to the agent what's available without forcing it to
# probe.
echo "palomar session-start: $("$BIN_DIR/cljfmt" --version)"
echo "palomar session-start: $("$BIN_DIR/clj-kondo" --version)"
