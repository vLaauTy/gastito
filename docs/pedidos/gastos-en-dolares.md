# Pedido: compras en dólares con la tarjeta de crédito

Leé `CLAUDE.md` y `docs/pedidos/tarjeta-ciclo.md` (si ya está hecho, integralo; si no, hacé este después). Trabajá nativo, migración nueva, tests, commit y push. Reporte de 5 líneas como máximo.

## El caso

A veces pago algo en dólares con la tarjeta de crédito (una suscripción, una compra online). En el momento sé el monto en dólares, pero no cuántos pesos me va a costar: eso aparece recién en el resumen (con el tipo de cambio y los impuestos de ese momento). Todos los totales del sistema siguen en pesos.

## Qué hacer

1. **Moneda en el movimiento:** columnas `moneda` (ARS por defecto, o USD), `monto_original` y `cotizacion_usada`. `monto` sigue siendo en pesos, y es lo que suman todos los totales.
2. **Interpretación:** "pagué 20 dólares de Steam con la tarjeta", "US$ 15 en Spotify" → moneda USD, monto original 20. Hoy un "20 dólares" no se puede guardar como $20.
3. **Pesos estimados:** Java calcula el monto en pesos con una **cotización de referencia** que cargo yo (`/dolar 1500`, "el dólar tarjeta está a 1500", o desde la web), y lo marca como **estimado** (la misma marca de los fijos estimados, si ya existe). Si no hay cotización cargada, el bot la pide con un botón **"No sé"**: en ese caso el gasto se guarda con el monto en pesos en 0 y "≈ sin estimar", y se muestra aparte.
4. **Cómo se ve:** "US$ 20 ≈ $30.000 (estimado)" en el bot, en Movimientos y en la tarjeta del ciclo.
5. **Corregir con el resumen:** cuando llega el resumen, cargo lo que me cobraron en pesos (por el bot: "lo de Steam salió 31.200", o desde la web con **Corregir monto**). Deja de ser estimado y se guarda la cotización real resultante.
6. **Ciclo de la tarjeta:** el próximo resumen muestra aparte "Compras en dólares: US$ 20 (≈ $30.000)". El aviso de diferencia con el total real del resumen tiene en cuenta que los montos en dólares son estimados.
7. **Solo para tarjeta de crédito** por ahora. Si digo dólares con otro medio de pago, preguntá.

## Tests

Interpretación con dólares, estimado con y sin cotización, corrección con el monto real, que los totales sumen en pesos, y cómo aparece en el ciclo de la tarjeta.
