#!/usr/bin/env bash

# wean.sh wrapper template for non-Nix systems

readonly WEAN="/path/to/wean.sh"
readonly WRAP="/path/to/wrapped/binary"

[[ -x "${WEAN}" ]] || { echo "Error: wean.sh not found at ${WEAN}" >&2; exit 1; }
[[ -x "${WRAP}" ]] || { echo "Error: wrapped binary not found at ${WRAP}" >&2; exit 1; }

source <(sed "s|@BINARY@|${WRAP}|g" "${WEAN}")
