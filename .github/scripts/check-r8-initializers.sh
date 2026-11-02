#!/usr/bin/env bash
# Release smoke without a device: asserts that every class instantiated by name at startup
# survives R8 with its no-arg constructor and is not renamed. Reads the R8 outputs of
# :app:assembleRelease (seeds.txt lists kept classes/members; mapping.txt lists renames).
set -euo pipefail

dir="${1:-app/build/outputs/mapping/release}"
classes=(
  "io.github.santimattius.android.startup.InitializationProvider"
  "com.santimattius.android.sample.SyncTestInitializer"
  "com.santimattius.android.sample.AsyncTestInitializer"
)

status=0
for fqn in "${classes[@]}"; do
  simple="${fqn##*.}"
  if grep -qxF "${fqn}: ${simple}()" "${dir}/seeds.txt"; then
    echo "OK   kept with no-arg constructor: ${fqn}"
  else
    echo "FAIL no-arg constructor not kept: ${fqn}" >&2
    status=1
  fi
  if grep -qxF "${fqn} -> ${fqn}:" "${dir}/mapping.txt"; then
    echo "OK   not renamed: ${fqn}"
  else
    echo "FAIL renamed or removed: ${fqn}" >&2
    status=1
  fi
done
exit "${status}"
