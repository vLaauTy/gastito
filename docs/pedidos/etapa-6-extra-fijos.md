# Pedido: gastos fijos con checklist mensual (extra de la etapa 6)

Leé `CLAUDE.md` antes de empezar. Trabajá **nativo** (sin subagentes ni el flujo de Superpowers): mostrame un resumen corto del enfoque en el chat y arrancá. Reutilizá lo que ya existe de la Parte C (`gasto_fijo`, scheduler, vínculo movimiento ↔ fijo); si hace falta cambiar la base, migración nueva. Todo se prueba con el perfil `prueba`.

## La idea

Los montos de mis fijos cambian todos los meses (luz, gas, Netflix). Siempre cargo **los pesos que me cobraron**, aunque el servicio sea en dólares: no hace falta manejar otra moneda. No quiero que el bot me proponga un monto para confirmar: yo le digo cuánto gasté y el sistema me muestra qué fijos me faltan cargar en el mes. El centro es un **checklist mensual**, en la web y en el bot.

## 1. Cargar varios fijos en un mensaje

"Gasté 90.000 en luz, 70000 en gas y 31500 en Netflix" (por texto o audio):

- La IA separa los gastos (monto y descripción). Java busca a qué gasto fijo activo corresponde cada uno (por nombre o alias, sin importar mayúsculas ni acentos) y lo vincula.
- Una sola tarjeta de confirmación con todos los gastos, cada uno con su ✅ o ❓:
  ```
  🧾 3 gastos
  ✅ Luz ........ $90.000  (fijo)
  ✅ Gas ........ $70.000  (fijo)
  ✅ Netflix .... $31.500  (fijo)
  ```
  Botones: **Guardar todo** / **Editar uno** / **Cancelar**. Si uno no coincide con ningún fijo, se guarda como gasto común (con su categoría, como siempre).
- Si ese fijo ya está cargado este mes, avisá en la tarjeta: "Ya cargaste la luz este mes ($88.000). ¿Es otro pago?".
- Esto también sirve para gastos comunes: varios gastos en un mensaje en general, no solo fijos.

## 2. Preparado para la etapa 7

En la etapa 7, el resumen de la tarjeta y los avisos de Galicia y Mercado Pago van a marcar fijos como cargados solos (por ejemplo `NETFLIX.COM` en el resumen → fijo Netflix). Dejá la vinculación movimiento ↔ fijo en un servicio que se pueda llamar desde ahí, y que los alias del fijo sirvan también para los nombres del resumen.

## 3. Checklist mensual

Para cada mes, la lista de los fijos activos que corresponden a ese mes (los bimestrales, solo en sus meses):

- ⏳ **Falta**: con el día aproximado del vencimiento y el monto del mes anterior como referencia (solo informativo, gris).
- ✅ **Cargado**: monto, fecha y diferencia contra el mes anterior ("+9%").
- ➖ **Este mes no**: lo salteo a mano.

Se actualiza solo cuando cargo el gasto desde el bot (o desde la web).

**En el bot:**
- `/fijos` muestra el checklist del mes con el total cargado y lo que falta.
- **Recordatorio:** en lugar de un mensaje por cada fijo, uno solo, cuando un fijo pasó su día de vencimiento sin cargarse: "Te falta cargar: luz (vence el 10), gas (vence el 12)". Como máximo uno cada 3 días (configurable), y también al arrancar si quedó algo pendiente.
- Los fijos de **monto fijo** que se debitan solos (el seguro por la tarjeta) se siguen proponiendo con el flujo actual de Confirmar / Cambió el monto / Este mes no. Que sea una opción de cada fijo: **"Proponer monto"** sí/no.

**En la web** (sección Fijos y cuotas):
- El checklist del mes arriba, con selector de mes para ver los anteriores.
- Desde cada fila: **Cargar monto** (crea el gasto igual que el bot), **Este mes no** y **Deshacer**.
- **ABM de gastos fijos:** agregar, editar y desactivar (sin borrar el historial). Campos: nombre, alias (para reconocerlo en los mensajes: "edesur", "luz"), categoría, medio de pago, cuenta o tarjeta, día aproximado, frecuencia (mensual o bimestral) y "Proponer monto".
- Historial de montos de cada fijo (los últimos 12 meses).

## Tests

- Separar varios gastos de un mensaje y vincular cada uno a su fijo (con acentos, alias, uno que no coincide).
- Checklist: mensual, bimestral, "este mes no", deshacer, y fijo ya cargado en el mes.
- Recordatorio agrupado y su frecuencia.
- Endpoints nuevos de la web.

## Al terminar

Suite completa una vez, regenerá los datos de prueba (con fijos, algunos cargados y otros pendientes en el mes actual), commit y push. Reporte de 10 líneas como máximo con cómo probarlo.
