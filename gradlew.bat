@echo off
rem ############################################################################
rem Gradle start up script for Windows
rem
rem This project's official gradle-wrapper.jar was missing when this fix was
rem applied (Bug Report 3.1) and could not be downloaded in that environment
rem (no network access) - only this launcher script and
rem gradle\wrapper\gradle-wrapper.properties could be restored. This is a
rem simplified stand-in for Gradle's own generated wrapper script, not a
rem byte-for-byte reproduction of it.
rem
rem Once you have this project open with ANY working Gradle install
rem (including Android Studio's own bundled one - just open the project and
rem let it sync), run this once from this directory to replace this file,
rem gradlew, and gradle-wrapper.jar with the official versions:
rem
rem   gradle wrapper --gradle-version 8.7
rem
rem After that, this note no longer applies.
rem ############################################################################

setlocal

set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if not exist "%WRAPPER_JAR%" (
    echo ERROR: %WRAPPER_JAR% not found. 1>&2
    echo This is the one piece this environment could not restore without network access ^(see Bug Report 3.1^). 1>&2
    echo Fix: open this project in Android Studio ^(it will offer to sync/regenerate the wrapper^), or run 1>&2
    echo      "gradle wrapper --gradle-version 8.7" once from this directory with any Gradle install you have. 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=Gradle" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
