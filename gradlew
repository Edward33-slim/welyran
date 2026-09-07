#!/bin/sh
# Downloads10 build launcher. GitHub Actions provisions Gradle via gradle/actions/setup-gradle.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
printf '%s\n' 'Gradle is not installed. Run this project in Android Studio, or provision Gradle before using ./gradlew.' >&2
exit 1
