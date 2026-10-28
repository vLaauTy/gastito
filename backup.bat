@echo off
rem Doble clic para hacer un backup de la base REAL (gastito) y de data\ en la carpeta BACKUP_DIR del .env.
rem El backup diario del Programador de tareas llama directo a scripts\backup.ps1 (ver README).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\backup.ps1" %*
pause
