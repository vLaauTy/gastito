# Pedido: el bot entiende lenguaje natural para todo, no solo para gastos

Leé `CLAUDE.md` y `docs/pedidos/fijo-por-voz.md` (este pedido lo incluye y lo amplía). Trabajá nativo, tests, commit y push. Reporte de 10 líneas como máximo.

## Regla nueva (agregala a `CLAUDE.md`)

**Todo lo que se puede hacer con un comando se puede pedir con un mensaje o un audio, dicho como sea.** Los comandos (`/fijo`, `/sueldo`, `/calcular`, etc.) quedan como atajos. El bot **nunca** le contesta al usuario "escribilo con este formato": si entiende, muestra la tarjeta de confirmación; si le falta un dato, lo pregunta con botones; si no está seguro de qué quiero, pregunta con botones las 2 o 3 opciones más probables.

## Qué hacer

1. **Inventario:** listá todos los comandos y flujos del bot y, para cada uno, si hoy se puede pedir en lenguaje natural. Mostrámelo en el reporte.
2. **Intenciones:** que la interpretación (la **misma llamada** a la IA de siempre, sin llamadas extra) reconozca todas las acciones, por ejemplo:
   - Gasto, varios gastos, juntada, cuotas, ingreso, sueldo, devolución, entre cuentas (ya existen).
   - Pregunta sobre los datos (ya existe).
   - **Alta de fijo** (ver `fijo-por-voz.md`) y **pagué un fijo** ("de luz pagué 87.500").
   - **Corregir o borrar el último movimiento** ("el último era 3500, no 3700", "borrá lo último que cargué").
   - **Cálculo de sueldo** ("este mes hice 10 horas al 50…").
   - **Consultas de listas:** "¿qué fijos me faltan pagar?", "¿quién me debe plata?", "¿cuánto llevo de IA este mes?", "mandame el resumen de la semana".
   - **Cancelar un plan de cuotas**, **dar por perdida una deuda**, etc.
3. **Errores de transcripción:** el prompt tiene que esperar errores de Whisper (palabras parecidas, números con letras, "lucas", "mil quinientos") e interpretar la intención igual.
4. **Datos faltantes → botones**, con opción "No sé" donde tenga sentido.
5. **Revisión de textos:** buscá en todo el código del bot los mensajes que le piden al usuario un formato o un comando ("usá /…", "escribí así: …") y reemplazalos por la pregunta con botones o por la interpretación.
6. **Ayuda:** `/ayuda` muestra ejemplos en lenguaje natural de cada cosa que se puede hacer, no la sintaxis de los comandos.

## Cuidado con el costo

Sumar intenciones agranda el prompt de interpretación. Mantenelo compacto (definiciones cortas y pocos ejemplos por intención) y contame en el reporte cuántos tokens de entrada tiene ahora y cuántos antes, según `uso_ia`.

## Tests

Con la IA simulada: una frase por intención (incluida una con error de transcripción), un caso ambiguo que pregunta con botones, y que ningún mensaje del bot pida un formato de comando.
