@echo off
rem Doble clic para arrancar Gastito con la base REAL (gastito).
rem "iniciar.bat prueba" lo arranca con la base de prueba (gastito_prueba), para probar cambios, y "iniciar.bat dev" con H2.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\iniciar.ps1" %*
