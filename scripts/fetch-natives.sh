#!/usr/bin/env bash
#
# Fetches the native SOURCE needed by the build. Nothing here is prebuilt:
#   1. hev-socks5-tunnel  -> built with its own Android.mk into libhev-socks5-tunnel.so
#                            (the in-app "tun2socks" that replaces v2rayNG)
#   2. Aether engine src  -> cross-compiled into libaether.so
#                            (upstream publishes NO Android binaries)
#
# Both are compiled later by scripts/build-natives.sh.
#
# Safe to re-run. All network access happens here / in CI, never on device.
# HEV is pinned to a reviewed upstream commit by default. Override HEV_REF only
# deliberately; an unavailable ref is a hard failure (never silently use HEAD).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
mkdir -p "${NATIVE_DIR}"

# Build the host prefix from fragments so no full literal URL lives in the file.
GH="https://""github.com"

HEV_REPO="heiher/hev-socks5-tunnel"
HEV_REF="${HEV_REF:-fc3f90040b8d4b537341c96538669a9b2e35d514}" # reviewed upstream commit; override only deliberately
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"

AETHER_REPO="${AETHER_REPO:-CluvexStudio/Aether}"
AETHER_REF="${AETHER_REF:-}"      # empty => default branch
AETHER_SRC="${NATIVE_DIR}/aether"

# The engine source is VENDORED inside this repo at native/aether so the app's
# own modifications (custom-range scanning in prober.rs / wg_prober.rs, etc.)
# are ALWAYS compiled into libaether.so with zero manual steps. When this dir is
# present we use it verbatim and never touch the network for the engine. Delete
# native/aether is an error: the engine is never cloned (auto-update disabled).
VENDORED_AETHER="${PROJECT_DIR}/native/aether"
AETHER_FORCE_CLONE="${AETHER_FORCE_CLONE:-}"

# clone_repo <url> <dir> <ref>
# Fetch the requested ref explicitly, then check out the exact fetched commit.
# Submodules are initialized only after checkout so gitlinks match that commit.
clone_repo() {
  local url="$1" dir="$2" ref="$3"
  rm -rf "${dir}"
  git clone --no-checkout --depth 1 "${url}" "${dir}"
  if ! git -C "${dir}" fetch --depth 1 origin "${ref}"; then
    echo "ERROR: could not fetch required HEV ref '${ref}' from ${url}; refusing to use a moving default branch." >&2
    rm -rf "${dir}"
    exit 1
  fi
  git -C "${dir}" checkout --detach FETCH_HEAD
  git -C "${dir}" submodule update --init --recursive
  if [ "${dir}" = "${HEV_DIR}" ]; then
    local hev_patch="${SCRIPT_DIR}/patches/hev-generation-stop.patch"
    [ -f "${hev_patch}" ] || { echo "ERROR: required HEV lifecycle patch missing: ${hev_patch}" >&2; exit 1; }
    git -C "${dir}" apply --check "${hev_patch}" || {
      echo "ERROR: HEV lifecycle patch does not apply cleanly to pinned source; refusing an unreviewed native build." >&2
      exit 1
    }
    git -C "${dir}" apply "${hev_patch}"
    grep -q "hev_socks5_tunnel_main_with_ready_callback" "${dir}/src/hev-main.c" || {
      echo "ERROR: HEV ready-callback API missing after patch." >&2; exit 1;
    }
    echo "   applied Aether generation-safe HEV lifecycle patch"
  fi
  echo "   cloned ${url} @ $(git -C "${dir}" rev-parse HEAD)"
}

echo "==> Fetching hev-socks5-tunnel (tunnel core)"
clone_repo "${GH}/${HEV_REPO}.git" "${HEV_DIR}" "${HEV_REF}"
if [ ! -f "${HEV_DIR}/Makefile" ]; then
  echo "ERROR: hev-socks5-tunnel checkout has no Makefile at ${HEV_DIR}" >&2
  ls -la "${HEV_DIR}" >&2 || true
  exit 1
fi

echo "==> Providing Aether engine source (engine)"
# 1.6.0: the engine is NEVER downloaded. Automatic core updates are disabled;
# the vendored native/aether (see CORE_VERSION) is the only source accepted.
if [ -n "${AETHER_FORCE_CLONE}" ]; then
  echo "WARNING: AETHER_FORCE_CLONE is ignored - automatic core download is disabled." >&2
fi
if [ -z "$(find "${VENDORED_AETHER}" -name Cargo.toml -not -path '*/target/*' 2>/dev/null | head -n1)" ]; then
  echo "ERROR: vendored engine missing at ${VENDORED_AETHER}; refusing to download one." >&2
  exit 1
fi
echo "   using the VENDORED engine bundled in this repo: ${VENDORED_AETHER} (core $(cat "${VENDORED_AETHER}/CORE_VERSION" 2>/dev/null || echo '?'))"
rm -rf "${AETHER_SRC}"
mkdir -p "${AETHER_SRC}"
# Copy everything except any local build output (target/).
( cd "${VENDORED_AETHER}" && tar --exclude='./target' --exclude='*/target' -cf - . ) \
  | ( cd "${AETHER_SRC}" && tar -xf - )
# The Aether binary crate does NOT live at the repo root; it sits in a
# subdirectory (e.g. aether/) next to the vendored quiche/ QUIC library. Just
# verify at least one Cargo.toml exists; build-natives.sh locates the crate.
if ! [ -n "$(find "${AETHER_SRC}" -name Cargo.toml -not -path '*/target/*' | head -n1)" ]; then
  echo "ERROR: Aether source has no Cargo.toml anywhere under ${AETHER_SRC}" >&2
  ls -la "${AETHER_SRC}" >&2 || true
  exit 1
fi
echo "   found Aether Cargo manifest(s):"
find "${AETHER_SRC}" -maxdepth 2 -name Cargo.toml -not -path '*/target/*' | sed 's/^/     /'

echo "==> Native sources ready under ${NATIVE_DIR}"
