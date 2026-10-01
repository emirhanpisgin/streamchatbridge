@echo off
rem Launches the mavenize queue in its own window (unattached).
rem Usage: launch-mavenize-queue.cmd [extra args, e.g. -Loaders neoforge]
start "Mavenize Queue" pwsh -NoProfile -NoExit -ExecutionPolicy Bypass -File "%~dp0mavenize-queue.ps1" %*
