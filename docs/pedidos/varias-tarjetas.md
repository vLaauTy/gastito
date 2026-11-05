# Pedido: más de una tarjeta de crédito (MasterCard Gold y Mercado Pago)

Leé `CLAUDE.md`. Trabajá nativo, migración nueva, tests, commit por parte y push. Antes de tocar la base real, `backup.bat`. Reporte de 10 líneas como máximo.

## La idea

Hasta ahora el sistema asume una sola tarjeta de crédito (MasterCard Gold de Galicia). Ahora tengo también la **tarjeta de crédito de Mercado Pago**. Cada compra con crédito tiene que saber de qué tarjeta es, y cada tarjeta tiene su propio ciclo (cierre, vencimiento, resumen, cuotas) y se paga desde su propia cuenta.

## Primero: inventario

Buscá en el código todo lo que hoy asume una sola tarjeta (por ejemplo búsquedas de "la" tarjeta, constantes, el seed, el ciclo de la tarjeta, cuotas, fijos, dashboard, prompts, generador de datos de prueba) y listá los archivos en el reporte. Usá `grep`, no leas archivos enteros.

## Tarjetas

| Tarjeta | Se paga desde | Palabras para reconocerla |
|---|---|---|
| MasterCard Gold (Galicia) | Caja de ahorro Galicia, débito automático | "master", "mastercard", "gold", "la de galicia", "galicia" |
| Tarjeta de crédito Mercado Pago | Cuenta de Mercado Pago | "la de mercado pago", "tarjeta de mercado pago", "mp" |

- Cada tarjeta guarda: nombre, emisor, cuenta desde la que se paga, alias (editables desde el dashboard) y un color o inicial para la web.
- Se pueden agregar o dar de baja tarjetas desde el dashboard (sin borrar el historial).
- **Migración:** todos los movimientos, cuotas, fijos y resúmenes de tarjeta que ya existen con crédito quedan asignados a **MasterCard Gold**.

## Reconocer la tarjeta

- Si digo la tarjeta ("con la master", "con la de mercado pago"), se usa esa.
- Si digo solo "crédito" o "tarjeta de crédito" y hay más de una activa, el bot pregunta con un botón por tarjeta. Mostrá primero la última que usé.
- **Ambigüedad con Mercado Pago:** "con mercado pago" solo, sin "tarjeta" ni "crédito", puede ser plata en cuenta (QR o transferencia) o la tarjeta. En ese caso preguntá: **Dinero en cuenta** / **Tarjeta de crédito MP**.
- Tickets: si el ticket muestra la marca de la tarjeta, usala como pista; si no, se pregunta como siempre.
- Un gasto con medio de pago crédito **no se guarda sin tarjeta**.

## Ciclo, resumen y pago (por tarjeta)

- Todo lo de `docs/pedidos/tarjeta-ciclo.md` pasa a ser **por tarjeta**: cada una con sus fechas de cierre y vencimiento (estimadas o confirmadas), su resumen, sus avisos y su recordatorio de fechas. La tarjeta de Mercado Pago arranca sin fechas: el bot me las pide.
- **Cuotas:** cada plan pertenece a una tarjeta y sus cuotas van a los resúmenes de esa tarjeta.
- **Dólares** (si `gastos-en-dolares.md` ya está hecho): las compras en dólares se separan por tarjeta.
- **Pago del resumen:** el `PAGO_TARJETA` sale de la cuenta de esa tarjeta (Galicia o Mercado Pago). "Pagué la tarjeta de mercado pago, 85.000" se registra contra esa tarjeta.
- **Fijos** con crédito: eligen la tarjeta.

## Qué veo

- **Bot:** la confirmación de una compra con crédito dice la tarjeta y en qué resumen entra ("💳 MasterCard Gold · entra en el resumen que vence ≈ 11/12"). El botón **💳 Tarjeta** del teclado muestra un bloque por tarjeta. "¿Cuánto llevo de la master?" responde solo esa.
- **Dashboard:** la sección Tarjeta con una pestaña o bloque por tarjeta, y el total entre las dos. En Movimientos, el medio de pago muestra la tarjeta. En "Por billetera" del Resumen, cada tarjeta por separado.

## Tests

Reconocimiento de cada tarjeta por sus alias, la pregunta cuando no se dice cuál, la ambigüedad "con mercado pago", la migración de lo existente a MasterCard Gold, el ciclo y las cuotas separados por tarjeta, y el pago de cada resumen desde su cuenta.
