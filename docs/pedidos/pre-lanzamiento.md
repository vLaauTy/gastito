# Pedido: preparar el uso con datos reales

Leé `CLAUDE.md`. Trabajá nativo, commits chicos, push al final y reporte de 10 líneas como máximo.

## 0. Si todavía no está hecho (ajustes de la etapa 8)

1. **Período del sueldo.** Cobro alrededor del día 5 el sueldo del mes anterior: es el caso normal. El ingreso de sueldo guarda la fecha de cobro (cuenta para el disponible de ese mes) y el **período** (mes trabajado), que vincula el cálculo y el recibo. Por defecto el período es el mes anterior si el cobro cae hasta el día 15 (configurable). La tarjeta lo muestra ("Sueldo de octubre · cobrado el 05/11") y se puede editar. El recibo se vincula solo por período; agregá también el botón de vincular a mano en el dashboard. Con tests.
2. **Inicio del registro** desde el **primer gasto**, no de cualquier movimiento: los meses con solo ingresos o recibos salen como "sin datos", no como $0. Con test.

## 1. Base real por defecto

- `iniciar.bat` arranca por defecto con el perfil `postgres` (base real). El perfil `prueba` solo con `iniciar.bat prueba`.
- Al arrancar, la consola y un mensaje de Telegram dicen con qué base está corriendo.
- El generador de datos de prueba se niega a correr si la base no es `gastito_prueba`.

## 2. Backup

- `scripts/backup.ps1`: `pg_dump` de la base real con la fecha en el nombre, más una copia de `data/`, a una carpeta configurable. Conserva los últimos 30.
- `scripts/restaurar.ps1` para restaurar un backup en una base nueva (para probar que el backup sirve sin tocar la real).
- En el README: cómo programar el backup diario con el Programador de tareas de Windows.

## 3. Arranque automático

Instrucciones en el README para que todo arranque solo al prender la PC: la máquina virtual de Ubuntu, el contenedor de PostgreSQL (`restart: unless-stopped`) y la app (Programador de tareas, con espera hasta que la base responda).

## 4. Secretos

Revisá que `.env` no esté en ningún commit del historial. Si está, avisame antes de tocar nada.
