#!/usr/bin/env bash

# wean: Self-discipline wrapper. Nags, then waits (geometrically longer
# per use of a particular binary, resetting each calendar day) before
# exec'ing the real one, with arguments intact.

set -euo pipefail

readonly BINARY='@BINARY@'
readonly STATE="${XDG_STATE_HOME:-${HOME}/.local/state}/wean.json"

today() {
  date +%Y%m%d
}

ensure-state() {
  mkdir -p -- "$(dirname -- "$STATE")"
  [[ -f "$STATE" ]] || printf '{}\n' > "$STATE"
}

# Read-increment-or-reset the counter for a single binary, persist it
# atomically, and echo the resulting count.
bump-count() {
  local name="$1"
  local now new tmp
  now=$(today)

  new=$(
    jq --null-input \
       --arg name "$name" \
       --arg today "$now" \
       --slurpfile prev "$STATE" \
       '($prev[0] // {}) as $s
        | ($s[$name] // {day:"", count:0}) as $e
        | $s + {($name):
            (if $e.day == $today
               then {day:$today, count:($e.count + 1)}
               else {day:$today, count:1}
             end)}'
  )

  tmp=$(mktemp -- "${STATE}.XXXXXX")
  printf '%s\n' "$new" > "$tmp"
  mv -f -- "$tmp" "$STATE"

  jq --raw-output --arg name "$name" '.[$name].count' <<< "$new"
}

setup-colours() {
  if [[ -t 1 ]]; then
    bold=$'\033[1m'; red=$'\033[31m'; dim=$'\033[2m'; reset=$'\033[0m'
  else
    bold=''; red=''; dim=''; reset=''
  fi
}

nag() {
  local count="$1"
  local timeout=$(( 3 + (2 ** count) ))
  local remaining

  printf '%s%sDo not overuse this! Use your brain, instead!%s\n' \
    "$bold" "$red" "$reset"

  for (( remaining = timeout; remaining > 0; remaining-- )); do
    printf '\r\033[K%sPaused: %2d s remaining...%s' "$dim" "$remaining" "$reset"
    sleep 1
  done
  printf '\r\033[K'
}

main() {
  local name count
  name=$(basename -- "$BINARY")

  # Refuse to be dodged mid-nag
  trap '' INT QUIT TSTP

  setup-colours
  ensure-state
  count=$(bump-count "$name")
  nag "$count"

  # IMPORTANT: restore default dispositions before exec
  trap - INT QUIT TSTP
  exec "$BINARY" "$@"
}

main "$@"
