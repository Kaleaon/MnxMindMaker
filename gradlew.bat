@echo off
setlocal

where gradle >NUL 2>&1
if %ERRORLEVEL% neq 0 (
  echo ERROR: 'gradle' was not found in PATH. 1>&2
  echo Install Gradle 8.2.1+ and ensure it is available on PATH. 1>&2
  exit /b 1
)

gradle %*
exit /b %ERRORLEVEL%
