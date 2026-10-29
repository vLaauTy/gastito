# Pedido: tildar fijos con el monto aproximado y corregirlo después

Leé `CLAUDE.md`. Trabajá nativo, migración nueva si hace falta, tests de cada regla, commit y push. Reporte de 5 líneas como máximo.

## La idea

Los fijos son mi lista de cosas que pago todos los meses. Quiero tildarlos rápido cuando pago, con el monto aproximado, y corregir el monto cuando sepa el real (por la web o diciéndoselo al bot).

## Cambios

1. **Tildar con el aproximado.** En el checklist, el botón principal de un fijo en "Falta" pasa a ser **Pagado**: crea el gasto con el monto aproximado del fijo (o el del mes anterior si es variable y lo hay), marcado como **estimado** (columna nueva en `movimiento`, por ejemplo `monto_estimado boolean`). Se mantienen "Este mes no" y, aparte, "Cargar monto" para poner el real de entrada.
2. **Cómo se ve:** en el checklist, un fijo pagado con monto estimado muestra "≈ $85.000 · estimado" y el botón **Corregir monto**. Al corregirlo, deja de ser estimado. En Movimientos también se ve la marca "estimado".
3. **Corregir monto** (web): diálogo como los demás del dashboard (no `window.prompt`), con teclado numérico. Cambia el monto del mismo gasto, sin borrarlo ni crear otro. Sirve también para los que ya tienen el monto real. Reemplazá también el `window.prompt` de "Cargar monto".
4. **Por el bot:** si digo "de luz pagué 87.500" y la luz de este mes ya está cargada **con monto estimado**, la tarjeta propone **Actualizar el monto ($85.000 → $87.500)** como opción principal, y "Es otro pago" como alternativa. Si ya estaba con el monto real, queda el aviso actual ("Ya cargaste la luz este mes").
5. **Desde el recordatorio de Telegram:** el mensaje agrupado de fijos vencidos trae un botón por fijo, "✅ Luz ≈ $85.000", que lo marca como pagado con el estimado, igual que en la web.
6. **Aviso de estimados:** el día 1 de cada mes, si quedaron fijos del mes anterior con monto estimado, el bot lo dice en una línea ("Quedaron con monto estimado: luz, gas. Si sabés el real, decímelo.") y el resumen mensual los cuenta igual, aclarando cuánto del total es estimado.

## Tests

Pagar con el estimado (fijo y variable), corregir el monto (deja de ser estimado y no crea otro movimiento), actualización por el bot sobre un estimado vs. sobre uno real, el botón del recordatorio y el aviso del día 1.
