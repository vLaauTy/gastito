# Pedido: ciclo de la tarjeta de crédito (cierre, vencimiento y cuánto llevo)

Leé `CLAUDE.md`. Trabajá nativo, migración nueva, tests de cada regla, commit por parte y push. Reporte de 10 líneas como máximo. Antes de arrancar con la base real, `backup.bat`.

## La idea

Las compras con crédito ya cuentan como gasto el día de la compra (no cambia). Lo que falta es saber **cuánto llevo para el próximo resumen** y **cuándo sale la plata de mi cuenta**. Tengo una sola tarjeta: MasterCard Gold de Galicia, que se paga por débito automático desde la caja de ahorro de Galicia.

## Modelo

- Tabla `resumen_tarjeta`: tarjeta, fecha de cierre, fecha de vencimiento, si las fechas son **estimadas o confirmadas**, total real del resumen (opcional, lo cargo yo), estado (abierto, cerrado, pagado) y el `PAGO_TARJETA` asociado.
- Cada compra con crédito y cada cuota pertenece al resumen cuyo período la incluye (compra después del cierre anterior y hasta el cierre de ese resumen). Se calcula, no se guarda a mano. **Las cuotas** van una por resumen a partir del primero que corresponde a la compra (alineá esto con `gastito.cuotas.primera-cuota`).
- Datos iniciales (en la base real, no en una migración): el resumen que **cerró el 29/10/2026 y vence el 11/11/2026**, con fechas confirmadas.

## Fechas que tardan en aparecer

El banco tarda en mostrar el próximo cierre y vencimiento. Por eso:
- Si el próximo resumen no tiene fechas, Java las **estima** a partir del anterior (mismo día del mes, ajustado si cae en fin de semana, y la misma distancia entre cierre y vencimiento) y las marca como **≈ estimadas** en todos lados.
- Las cargo cuando las sepa, por el bot en lenguaje natural ("la tarjeta cierra el 26/11 y vence el 11/12") o desde la web. Al confirmarlas, las compras se reasignan solas al resumen que corresponde.
- **Recordatorio:** 2 días después del cierre del resumen anterior, si el próximo sigue estimado, el bot pregunta una vez por día como máximo: "¿Ya sabés el próximo cierre y vencimiento de la tarjeta?", con el botón **Todavía no** (vuelve a preguntar en 3 días).

## Qué veo

- **Dashboard** (tarjeta en Resumen y detalle en una sección o pestaña "Tarjeta"):
  - **Resumen cerrado por pagar:** "Vence el 11/11 · según lo cargado $X".
  - **Próximo resumen:** "Cierra ≈ 26/11 · vence ≈ 11/12 · llevás $Y (compras $A + cuotas $B)".
  - La lista de compras de cada resumen.
  - Las cuotas que ya están comprometidas para los resúmenes siguientes.
- **Bot:** la tarjeta de una compra con crédito suma una línea "Entra en el resumen que vence ≈ 11/12". Preguntas como "¿cuánto llevo de tarjeta?" o "¿cuánto vence el 11?" responden sin IA si son evidentes (como los pedidos de listas). Sumá un botón **💳 Tarjeta** al teclado fijo.

## Vencimiento y pago

- **3 días antes del vencimiento:** "El 11/11 vence la tarjeta: según lo cargado, $X. Se debita de Galicia." Con el botón **Cargar total del resumen**.
- **Si cargo el total real** (por el bot: "el resumen de la tarjeta es de 250.000", o en la web) y difiere de lo cargado: "El resumen dice $W y cargaste $X: faltan $D sin cargar." No crea gastos solo; es un aviso para que revise.
- **El día del vencimiento:** "¿Se debitó el resumen de la tarjeta ($W)?" con **Sí** / **Todavía no**. Con Sí se crea el `PAGO_TARJETA` (no cuenta como gasto, regla 5) desde la cuenta Galicia y el resumen queda pagado. Si yo digo "se debitó la tarjeta, 250.000", hace lo mismo.

## Resumen mensual de un mes parcial

Si el registro empezó dentro del mes (por ejemplo, el primer gasto es del 31/10), el resumen mensual de ese mes dice que es parcial ("Registro desde el 31/10") y no hace comparaciones ni consejos sobre ese mes.

## Tests

Asignación de compras y cuotas a resúmenes (en el límite del cierre), fechas estimadas y su reasignación al confirmarlas, recordatorio de fechas (y "Todavía no"), aviso previo al vencimiento, diferencia con el total real, pago del resumen que no cuenta como gasto, y el resumen mensual parcial.
