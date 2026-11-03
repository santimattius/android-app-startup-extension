#!/usr/bin/env bash
# Release smoke without a device: asserts that every class instantiated by name at startup
# survives R8 with its no-arg constructor and is not renamed. Reads the R8 outputs of
# :app:assembleRelease (seeds.txt lists kept classes/members; mapping.txt lists renames).
#
# Initializers declared in the app manifest are kept by AGP's aapt2 rules on their own, so
# they do not prove that core/consumer-rules.pro works. DependencyOnlyInitializer is NOT in
# the manifest (it is only reached through SyncTestInitializer.dependencies()), so it is kept
# solely by the consumer rules: if those rules regress, this script fails.
# ExampleLoggerInitializer is intentionally absent: it belongs to androidx.startup's provider,
# not to this library's graph.
set -euo pipefail

dir="${1:-app/build/outputs/mapping/release}"
classes=(
  "io.github.santimattius.android.startup.InitializationProvider"
  "com.santimattius.android.sample.SyncTestInitializer"
  "com.santimattius.android.sample.AsyncTestInitializer"
  "com.santimattius.android.sample.DependencyOnlyInitializer"
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
