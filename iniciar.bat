@echo off
rem Doble clic para arrancar Gastito con PostgreSQL. "iniciar.bat dev" lo arranca con H2.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\iniciar.ps1" %*
