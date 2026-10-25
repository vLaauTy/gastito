@echo off
rem Borra los datos de la base de PRUEBA (gastito_prueba) y la llena de nuevo con unos 3 meses de datos inventados.
rem Nunca toca la base real: el generador comprueba que la base sea gastito_prueba antes de borrar nada.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\generar-datos-prueba.ps1" %*
