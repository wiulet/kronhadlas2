#!/bin/sh

##############################################################################
# Gradle start up script for POSIX (Linux/macOS/WSL/a normal terminal)
#
# This project's official gradle-wrapper.jar was missing when this fix was
# applied (Bug Report 3.1) and could not be downloaded in that environment
# (no network access) - only this launcher script and
# gradle/wrapper/gradle-wrapper.properties could be restored. This script
# covers the common case; it is a simplified stand-in for Gradle's own
# generated wrapper script, not a byte-for-byte reproduction of it (which
# additionally handles Cygwin/MSYS/Darwin path quirks and a few other legacy
# edge cases this version leaves out).
#
# Once you have this project open with ANY working Gradle install (including
# Android Studio's own bundled one - just open the project and let it sync,
# or use Android Studio's "Upgrade Gradle Wrapper" prompt if one appears),
# run this once from this directory to replace this file, gradlew.bat, and
# gradle-wrapper.jar with the official versions:
#
#   gradle wrapper --gradle-version 8.7
#
# After that, this note no longer applies.
##############################################################################

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
APP_NAME="Gradle"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: $WRAPPER_JAR not found." >&2
    echo "This is the one piece this environment could not restore without network access (see Bug Report 3.1)." >&2
    echo "Fix: open this project in Android Studio (it will offer to sync/regenerate the wrapper), or run" >&2
    echo "     'gradle wrapper --gradle-version 8.7' once from this directory with any Gradle install you have." >&2
    exit 1
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVACMD="java"
else
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found on your PATH." >&2
    exit 1
fi

exec "$JAVACMD" $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_NAME" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
