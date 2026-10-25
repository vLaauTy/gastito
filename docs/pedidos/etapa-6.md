
Leé `CLAUDE.md` antes de empezar. Vamos con la **Etapa 6**. Guardá este pedido en `docs/pedidos/etapa-6.md`. Armá un plan y mostrámelo antes de tocar código; si el plan es largo, proponé dividirlo en partes con un commit por parte. Recordá: todo se prueba con el perfil `prueba` (base `gastito_prueba`), los cambios de base van en migraciones nuevas, y los cálculos de plata los hace Java, nunca la IA.

El diseño de referencia está en el prototipo: pantallas de compra repetida, juntadas, "te entró plata" y gastos fijos. Lo que sigue manda sobre el prototipo si hay diferencias.

## Parte A: compras repetidas

Cuando entra un gasto nuevo (texto, audio o ticket), antes de mostrar la tarjeta de confirmación, buscá si ya hay uno **muy parecido**:
- mismo monto (con una tolerancia del 1% o $50, lo que sea mayor),
- dentro de las 3 horas anteriores o posteriores (configurable),
- y misma categoría o mismo comercio, si los dos los tienen.

Si lo hay, en lugar de la tarjeta normal mostrá los dos lado a lado ("Ya registrado" / "Nuevo") con dos botones:
- **Es la misma: uní los datos.** Queda un solo movimiento con lo mejor de cada uno: el detalle de productos del ticket, la descripción del audio o del texto, el medio de pago que esté más completo, y la hora más precisa. El origen pasa a ser el del más completo (el ticket gana) y se guarda que hubo una unión.
- **Compré de nuevo: guardá las dos.**

En la etapa 7 van a llegar avisos automáticos de Galicia y Mercado Pago: diseñá la detección para que también sirva para unir un aviso con un audio o un ticket.

## Parte B: juntadas

Ejemplo: "pagué 48000 el asado, éramos 6, con mercado pago".

- La IA detecta la juntada y extrae el **total pagado** y la **cantidad de personas** (o los nombres, si los digo: "con Martín, Sofi y Pablo"). La división la hace **Java**: mi parte = total / personas, redondeando a peso; la diferencia de redondeo me la quedo yo.
- **Cómo se cuenta:**
  - En mis gastos cuenta **solo mi parte** ($8.000), con la marca `juntada`.
  - El resto ($40.000) queda como **plata a cobrar**, asociada a la juntada y a la cuenta desde donde pagué.
- **Tarjeta de confirmación** como la del prototipo: Pagaste / Tu gasto real / A cobrar, y la lista de personas con su estado. Si no dije nombres, quedan como "Persona 1…5" y las puedo renombrar con un botón.
- **Partes distintas:** puedo cambiar el monto de una persona ("Martín no comió carne, puso 5000"). Java recalcula las demás partes para que el total siga cerrando.
- **Cobrar:** cuando digo "Pablo me pasó 8000" o "me devolvieron 8000 del asado", el bot lo registra como **`DEVOLUCION`** (no es ingreso, regla 5 de `CLAUDE.md`) y marca a esa persona como pagada. Si no queda claro de qué juntada es, me pregunta con botones (las juntadas con deudas pendientes).
- **Recordatorio:** botón **Armar mensaje** que me da un texto listo para copiar y mandar al grupo, con quién debe cuánto. El bot no les escribe a otras personas.
- **Deuda perdida:** si alguien no me paga, puedo tocar **Dar por perdido**. Esa parte pasa a ser gasto mío, con la misma categoría y la fecha original.
- Datos: tabla `juntada` (movimiento, total pagado, cantidad de personas) y `juntada_participante` (nombre, monto, estado: pendiente / pagado / perdido, fecha de pago y la devolución asociada).

## Parte C: gastos fijos

Los que se repiten cada mes: el seguro (en la tarjeta), streaming, luz, gas, servicios sociales.

- Tabla `gasto_fijo`: nombre, monto esperado, categoría, medio de pago y cuenta o tarjeta, día del mes, frecuencia (mensual o bimestral), si el monto es fijo o variable, y si está activo.
- **Alta:** desde el bot (`/fijo seguro 32900 tarjeta día 4`, o en lenguaje natural "agregá un fijo: …") y desde el dashboard.
- **Cada mes, el día del cobro**, el bot crea el movimiento como `PENDIENTE` y me pregunta: "Se cobró el seguro: $32.900. ¿Confirmás?", con **Confirmar**, **Cambió el monto** y **Este mes no**. En la etapa 7 el resumen de la tarjeta lo va a confirmar solo; dejalo preparado.
- **Montos variables** (luz, gas): el monto propuesto es el promedio de los últimos 3 cobros confirmados.
- **Aviso de aumento:** si el monto confirmado difiere más de un 5% del anterior: "El seguro subió de $32.900 a $36.100 (+9,7%)".
- Si la PC estuvo apagada el día del cobro, al arrancar pregunta por los fijos que quedaron sin registrar.
- Los gastos fijos se registran como gastos normales, con la marca `fijo` y el vínculo a su `gasto_fijo`.

## Parte D: compras en cuotas

Ejemplo: "zapatillas 84000 en 6 cuotas con la tarjeta".

- Se guarda un **plan de cuotas** (total, cantidad de cuotas, valor de la cuota, primer mes, tarjeta), con la cuota calculada por Java. Si digo el valor de la cuota en lugar del total ("6 cuotas de 14000"), también vale.
- **Cómo se cuenta:** en cada mes cuenta como gasto **solo la cuota de ese mes**. Cada cuota es un movimiento, generado automáticamente y ya confirmado, con "cuota 3 de 6" en la descripción.
- La primera cuota se imputa al mes de la compra (configurable, por si mi tarjeta la cobra al mes siguiente).
- Puedo cancelar un plan (si lo pagué entero antes): se borran las cuotas futuras, no las pasadas.

## Parte E: ingresos y devoluciones por mensaje

Hoy los ingresos se cargan con `/sueldo`, `/aguinaldo` e `/ingreso`. Sumá que se puedan decir en lenguaje natural ("me pagaron 25000 por arreglar una PC", "me pasaron 8000"):
- Si queda claro que es un ingreso, la tarjeta de confirmación es la de ingresos.
- Si no queda claro, preguntá **¿Qué es esta plata?** con botones: **Trabajo particular**, **Devolución de juntada**, **Entre mis cuentas** y **Otro ingreso**, como en el prototipo.
- **Entre mis cuentas** se guarda como `ENTRE_CUENTAS`, con la cuenta de origen y la de destino, y no cuenta ni como gasto ni como ingreso.
- La detección automática por el remitente de la transferencia queda para la etapa 7, con los avisos.

## Parte F: dashboard, resúmenes y preguntas

- **Dashboard:** se habilitan las secciones **Fijos y cuotas** (total fijo por mes, lista con su próximo cobro, historial de montos de cada uno, cuotas pendientes y lo comprometido en los próximos 6 meses) y **Juntadas** (abiertas, con quién debe cuánto, y cerradas). En el Resumen, la tarjeta de juntadas muestra la plata a cobrar.
- **Disponible proyectado:** debajo del disponible actual, una línea "Si se cobran los fijos y cuotas que faltan este mes: $X".
- **Resúmenes:** suman plata a cobrar de juntadas, fijos que aumentaron y cuotas del mes (solo como datos del JSON, con la regla de no repetir y el control de números).
- **Preguntas:** nuevas herramientas: `juntadasPendientes()`, `gastosFijos()` y `cuotasPendientes()`. Ejemplos: "¿quién me debe plata?", "¿cuánto pago de fijos por mes?", "¿cuántas cuotas me quedan de las zapatillas?".

## Datos de prueba

Actualizá el generador del perfil `prueba` para que también cree: 3 o 4 juntadas (con deudas pagadas, pendientes y una perdida), 5 gastos fijos con sus cobros mensuales (uno con un aumento), 2 compras en cuotas y algún par de compras repetidas.

## Tests

Sin Docker ni API real:
- Detección de repetidos: dentro y fuera de la tolerancia y de la ventana de tiempo, y la unión que conserva lo mejor de cada uno.
- Juntadas: división con redondeo, partes distintas que siguen cerrando, devolución, deuda perdida que pasa a gasto, y que las devoluciones no sumen como ingreso.
- Fijos: generación del cobro, montos variables (promedio), aviso de aumento y cobros pendientes al arrancar.
- Cuotas: generación, mes de la primera cuota, cancelación y lo comprometido a futuro.
- `ENTRE_CUENTAS`, que no afecte gastos ni ingresos.
- Endpoints nuevos del dashboard y las herramientas de las preguntas.

## Al terminar

Corré los tests, regenerá los datos de prueba, levantá con el perfil `prueba` y decime cómo probar cada parte desde Telegram y en el dashboard. Commits chicos y al final subí todo a GitHub.
