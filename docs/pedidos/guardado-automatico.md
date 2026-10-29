# Pedido: guardado automático en el bot y borrar/editar gastos en el dashboard

Leé `CLAUDE.md`. Trabajá nativo, tests de cada regla, commit por parte y push. Reporte de 10 líneas como máximo.

## Parte 1: guardado automático en Telegram

Cambio la **regla 3 de `CLAUDE.md`** ("confirmar antes de guardar"). Actualizala así: **los gastos claros se guardan solos y se pueden deshacer o editar; se pide confirmación solo cuando hay una duda real.**

**Se guarda solo** (sin botón Guardar) un gasto simple de texto, audio o ticket cuando:
- el monto está claro,
- la categoría tiene confianza alta,
- y no hay ninguno de los casos de abajo.

La respuesta queda así:
```
✅ Guardado · $3.700 · 🍫 Kiosco · 💵 Efectivo
   Pan de pancho y papas
[✏️ Editar] [↩️ Deshacer]
```
- **Deshacer** borra el movimiento (y sus items de ticket). Funciona sobre ese mensaje en cualquier momento, no solo los primeros segundos.
- **Editar** abre los mismos campos de siempre sobre el movimiento ya guardado.
- **Medio de pago no dicho:** no frena el guardado. Se guarda como "Sin especificar" y el mensaje agrega una fila de botones rápidos (💵 Efectivo · 💳 Débito · 💳 Crédito · 🏦 Transf. · QR). Tocar uno lo actualiza; si no toco nada, queda "Sin especificar".

**Sigue pidiendo confirmación** (con los botones de siempre) cuando:
- la categoría es dudosa (las opciones de categoría que ya ofrece),
- parece una **compra repetida**,
- es una **juntada**, **cuotas**, **varios gastos** en un mensaje con alguno dudoso, un **ingreso o sueldo**, el **alta de un fijo**, o cualquier cosa que no sea un gasto simple,
- un **ticket** cuyos productos no cierran con el total, o que dice solo "TARJETA" (débito o crédito),
- el monto es inusualmente alto: más de $100.000 (configurable).

**Configuración:** `gastito.bot.guardado-automatico: true` y el tope en `application.yml`. Con `false` vuelve al comportamiento actual.

## Parte 2: borrar y editar gastos en el dashboard

En **Movimientos** (y en "Últimos movimientos" del Resumen):
- Cada fila tiene un menú **⋯** con **Editar** y **Eliminar**. En el celular, dentro de la tarjeta desplegada.
- **Eliminar:** diálogo de confirmación ("¿Eliminar Pan de pancho y papas · $3.700?") y, después, un aviso con **Deshacer** durante unos segundos.
- **Editar:** el mismo diálogo para monto, fecha, descripción, categoría, medio de pago y cuenta.
- **Casos especiales:** una juntada, una cuota o un fijo no se borran como un gasto común. Ofrecé lo que corresponda (borrar la juntada entera, cancelar el plan de cuotas desde esa cuota, deshacer el fijo del mes) o explicá por qué no se puede.
- Endpoints `PUT` y `DELETE` `/api/movimientos/{id}`, con tests. Borrar un movimiento con ticket borra también sus items; la foto queda en disco.

## Tests

Guardado automático vs. confirmación (un test por cada caso de la lista), Deshacer y Editar sobre uno guardado, medio de pago rápido, la configuración en `false`, y los endpoints nuevos con los casos especiales.
