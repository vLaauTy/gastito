# Pedido: viajes (Bariloche) y préstamos en cuotas

Leé `CLAUDE.md`. Si ya están hechos, integrá con `inversiones.md` (reservas para metas), `tarjeta-ciclo.md` / `varias-tarjetas.md` y las juntadas. Trabajá nativo, migración nueva, tests, commit por parte y push. Antes de tocar la base real, `backup.bat`. Reporte de 10 líneas como máximo.

## Parte A: préstamos a personas, devueltos en cuotas

Le pagué parte del pasaje a un amigo y me lo devuelve en cuotas. Sirve también fuera de los viajes.

- Tabla `prestamo`: persona, monto total, cantidad de cuotas, valor de la cuota (Java calcula el que falte), primer mes, día aproximado, cuenta desde la que salió, viaje asociado (opcional) y estado. Cada cuota con su mes, monto y estado (pendiente, cobrada, atrasada).
- **No es gasto mío**: la plata prestada queda "a cobrar", como la parte de los demás en una juntada.
- **Cargarlo junto con el gasto:** "pagué 293.000 de vuelos por transferencia; 30.000 son de Martín, que me los devuelve en 6 cuotas de 5.000 desde diciembre" → un gasto mío por la diferencia (en el viaje) y un préstamo a Martín, de la misma transferencia. La tarjeta de confirmación muestra las dos partes.
- **Cobrar:** "Martín me pasó la cuota" o "Martín me pasó 5.000" → `DEVOLUCION` (no es ingreso), marca la cuota que corresponda y dice cuánto falta ("Martín: 1 de 6 cuotas, faltan $25.000"). Si paga de más o de menos, se acomoda en las siguientes cuotas.
- **Recordatorio:** si una cuota pasa unos días de su fecha sin cobrarse, el bot avisa una vez y ofrece **Armar mensaje** (como en las juntadas). El bot no le escribe a nadie.
- `/prestamos` y "¿quién me debe?" incluyen los préstamos junto con las juntadas.

## Parte B: viajes

- Tabla `viaje`: nombre, fecha de inicio y de fin, cantidad de personas (opcional), presupuesto (opcional: **no es obligatorio**), notas.
- **Movimientos del viaje:** cualquier movimiento puede asociarse a un viaje (columna `viaje_id`). La fecha del gasto sigue siendo la real; el viaje es una etiqueta.
- **Lo que falta pagar:** lista de pendientes del viaje (hospedaje, traslados, excursiones…) con monto aproximado **opcional**, y se tildan al pagarlos (crea el gasto asociado, como los fijos). Por el bot: "para Bariloche me falta el hospedaje, ≈ 400.000".
- **Modo viaje:** entre la fecha de inicio y la de fin (incluidas), todo lo que cargo se asocia solo al viaje. La confirmación lo dice ("✅ Guardado · 🏔 Bariloche") con un botón **No es del viaje**. El primer día el bot avisa "Arrancó el modo viaje: Bariloche" y el último lo cierra.
- **Gastos compartidos en el viaje:** las juntadas cargadas durante el viaje quedan asociadas a él.
- **Cuánto ahorrar:** "Para Bariloche falta pagar ≈ $X (sin contar lo que no tiene monto). Hasta el inicio quedan N cobros de sueldo: separá ≈ $X/N por mes". Si hay plata reservada de inversiones para el viaje, se descuenta.

## Qué veo

- **Pestaña Viajes** en el dashboard: por viaje, **Pagado $A · Falta ≈ $B · Total estimado $C**, lo que me deben por el viaje (préstamos y juntadas), la lista de pendientes, los gastos por categoría y por día, y cuánto me costó a mí contra lo que puse por los demás. Si hay presupuesto, la barra contra el presupuesto; si no, solo los totales.
- **Durante el viaje:** el botón "Hoy" del bot incluye "Bariloche: llevás $X en N días (≈ $Y por día)".
- **Resúmenes mensuales:** si un mes tiene gastos de un viaje, muestran el total y "**sin el viaje** gastaste $X", y las comparaciones y consejos se hacen **sin el viaje**.
- **Después del viaje:** resumen final por mensaje (total, por categoría, por día, por persona si hay cantidad de personas, y lo que falta cobrar).

## Tests

Préstamo cargado junto con el gasto (división correcta), cuotas cobradas, de más y de menos, cuota atrasada; viaje con pendientes con y sin monto, modo viaje automático y "No es del viaje", cálculo de cuánto ahorrar (con y sin reservas), y el resumen mensual sin el viaje.
