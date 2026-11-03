#!/usr/bin/env bash
# Guards the published POM of :core: it must never depend on androidx.tracing or
# tracing-wire. android.os.Trace is the only tracer (see the androidx.tracing 2 ADR);
# a tracing dependency would leak into every consumer's graph and, through tracing-wire,
# into Macrobenchmark runs. Run after :core:generatePomFileForMavenPublication and
# :core:generateMetadataFileForMavenPublication.
# Rules: the POM is mandatory (exit 1 when missing). The Gradle module metadata, which Gradle
# consumers read instead of the POM, is checked too when present and skipped when absent.
set -euo pipefail

pom="${1:-core/build/publications/maven/pom-default.xml}"
module="${2:-$(dirname "${pom}")/module.json}"

if [[ ! -f "${pom}" ]]; then
  echo "FAIL POM not found: ${pom} (run :core:generatePomFileForMavenPublication first)" >&2
  exit 1
fi

if grep -nE 'androidx\.tracing|tracing-wire' "${pom}" >&2; then
  echo "FAIL ${pom} references androidx.tracing or tracing-wire" >&2
  exit 1
fi

if [[ -f "${module}" ]]; then
  if grep -nE 'androidx\.tracing|tracing-wire' "${module}" >&2; then
    echo "FAIL ${module} references androidx.tracing or tracing-wire" >&2
    exit 1
  fi
  echo "OK   no androidx.tracing / tracing-wire in ${module}"
else
  echo "SKIP Gradle module metadata not found: ${module}"
fi

echo "OK   no androidx.tracing / tracing-wire in ${pom}"
