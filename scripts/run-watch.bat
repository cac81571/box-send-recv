@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0.."
mvn compile exec:java "-Dexec.args=%*"
endlocal
