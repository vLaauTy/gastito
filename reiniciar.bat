@echo off
rem Cierra el Gastito que esta corriendo (solo ese proceso y su ventana), recompila si cambio el codigo y lo vuelve a abrir
rem con el mismo perfil. "reiniciar.bat prueba" lo vuelve a abrir con la base de prueba.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\iniciar.ps1" -Reiniciar %*
