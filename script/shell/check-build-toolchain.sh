#!/usr/bin/env bash
# Usage: bash script/shell/check-build-toolchain.sh with the intended JDK and Maven on PATH.
set -euo pipefail

java_settings=$(java -XshowSettings:properties -version 2>&1)
java_version=$(awk -F ' = ' '/^[[:space:]]*java.specification.version = / { print $2 }' <<< "$java_settings")
java -version
javac_version=$(javac -version)
printf '%s\n' "$javac_version"

maven_version=$(mvn -version)
printf '%s\n' "$maven_version"

if [[ $java_version != 21 || ! $javac_version =~ ^javac[[:space:]]21([.+-]|$) ]]; then
    echo 'Build requires JDK 21 (java and javac).' >&2
    exit 1
fi
if ! grep -Eq 'Apache Maven 3[.]9[.]12([[:space:]]|$)' <<< "$maven_version"; then
    echo 'Build requires Apache Maven 3.9.12.' >&2
    exit 1
fi
if ! grep -Eq '^Java version: 21([.+-]|,|$)' <<< "$maven_version"; then
    echo 'Maven must run on Java 21. Check JAVA_HOME and PATH.' >&2
    exit 1
fi
