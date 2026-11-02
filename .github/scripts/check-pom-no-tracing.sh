#!/usr/bin/env bash
# Guards the published POM of :core: it must never depend on androidx.tracing or
# tracing-wire. android.os.Trace is the only tracer (see the androidx.tracing 2 ADR);
# a tracing dependency would leak into every consumer's graph and, through tracing-wire,
# into Macrobenchmark runs. Run after :core:generatePomFileForMavenPublication.
set -euo pipefail

pom="${1:-core/build/publications/maven/pom-default.xml}"

if [[ ! -f "${pom}" ]]; then
  echo "FAIL POM not found: ${pom} (run :core:generatePomFileForMavenPublication first)" >&2
  exit 1
fi

if grep -nE 'androidx\.tracing|tracing-wire' "${pom}" >&2; then
  echo "FAIL ${pom} references androidx.tracing or tracing-wire" >&2
  exit 1
fi

echo "OK   no androidx.tracing / tracing-wire in ${pom}"
