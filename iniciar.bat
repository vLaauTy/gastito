@echo off
rem Doble clic para arrancar Gastito con la base de PRUEBA (gastito_prueba).
rem "iniciar.bat postgres" lo arranca con la base real y "iniciar.bat dev" con H2.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\iniciar.ps1" %*
