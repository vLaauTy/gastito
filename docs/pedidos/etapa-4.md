# Pedido para Claude Code: Etapa 4 (resúmenes y preguntas)

Pegale a Claude Code todo lo que está debajo de la línea.

---

Leé `CLAUDE.md` antes de empezar. Vamos con la **Etapa 4: resúmenes automáticos y preguntas en lenguaje natural**. Armá un plan y mostrámelo antes de tocar código. Guardá este pedido en `docs/pedidos/etapa-4.md`, como hiciste con la etapa 3. Todo tiene que correr y testearse sin Docker en Windows; los cambios de base van en una migración nueva.

## Regla principal

**Los números los calcula Java, la IA solo los cuenta** (regla 2 de `CLAUDE.md`). Para cada resumen o respuesta, Java arma un JSON con los datos ya calculados, y la IA solo redacta el texto a partir de ese JSON.

## Parte 1: resúmenes automáticos

### Cuándo
- **Semanal: domingo a las 21:00**, sobre la semana de lunes a domingo que termina ese día.
- **Mensual: el día 1 a las 9:00**, sobre el mes que terminó.
- Zona horaria `America/Argentina/Buenos_Aires`, configurable en `application.yml`.
- **Si la PC estaba apagada** a esa hora, el resumen pendiente se manda apenas arranca la aplicación. Guardá cuáles ya se mandaron, para no repetirlos ni salteárselos.
- Comandos para pedirlos a mano: `/resumen` (la semana en curso hasta hoy) y `/resumen mes` (el mes en curso hasta hoy).

### Qué datos calcula Java
- Total gastado, cantidad de gastos y comparación con el período anterior **hasta el mismo punto**.
- Gasto por categoría, con la variación de cada una contra el período anterior.
- El gasto más grande del período.
- **Gastos hormiga:** compras de menos de $3.000 (configurable) en Kiosco y Comida afuera: cuántas son y cuánto suman.
- Días sin gastos.
- **Productos más comprados**, a partir del detalle de los tickets: los 5 con más unidades o más plata en el período.
- Solo en el mensual: ingresos (sueldo, aguinaldo, extras), disponible, el porcentaje de los ingresos que se gastó, y el costo de la IA del mes.
- Datos que todavía no se pueden calcular bien, como "Sin especificar" o la plata pendiente de juntadas, van con una aclaración, no inventados.

### Qué escribe la IA
- Con Haiku: de 2 a 4 observaciones cortas y **un solo consejo concreto**, con un número ("si bajás el kiosco a 3 veces por semana, ahorrás unos $X por mes"), basado únicamente en el JSON. El modelo es configurable, por si para el mensual quiero probar uno más potente.
- Tono: directo y amable, en castellano rioplatense (vos), sin sermones.
- Prompts en `src/main/resources/prompts/resumen-semanal.txt` y `resumen-mensual.txt`.
- **Control de números inventados:** antes de mandar el texto, verificá en Java que cada monto y cada porcentaje que aparece en él exista en el JSON (con tolerancia de redondeo). Si aparece un número que no está, se descarta el texto de la IA y se manda solo el resumen numérico, sin la parte de la IA. Esto lleva tests.

### Cómo se ve en Telegram
```
📊 Tu semana · 13 al 19 de octubre
Gastaste $52.300 (+12% que la semana pasada)

🛒 Supermercado ▇▇▇▇▇▇ $21.400
🍫 Kiosco       ▇▇▇▇   $14.800
🏍 Transporte   ▇▇▇    $9.600

🐜 Gastos hormiga: 11 compras chicas, $14.800
🏆 Lo que más compraste: Seven Up lata (4)

💡 Lo que noté
• …
• …
👉 Consejo: …
```
Con un botón **Ver en el dashboard**, si se abre desde la PC.

### Dashboard
La tarjeta **Lo que notó la IA** (hoy dice "Próximamente") muestra las observaciones y el consejo del último resumen. Agregá un endpoint `GET /api/resumenes/ultimo` y guardá cada resumen en una tabla `resumen` (tipo, período, datos en JSON, texto, fecha de envío).

## Parte 2: preguntas en lenguaje natural

Quiero poder escribirle o mandarle un audio al bot con preguntas como:
- "¿Cuánto gasté en kiosco este mes?"
- "¿En qué gasté más la semana pasada?"
- "¿Cuánto llevo gastado en Día desde septiembre?"
- "¿Cuántas Seven Up compré este mes?"
- "¿Cuánto me queda disponible?"
- "Compará octubre con septiembre"

### Cómo distinguir pregunta de gasto
El interpretador hoy decide si un mensaje es un gasto (`esGasto`). Cambialo por una **intención**: `GASTO`, `PREGUNTA` u `OTRO`. "3700 en pan" es un gasto; "¿cuánto gasté en pan?" es una pregunta. Ante la duda, el bot pregunta con dos botones: **Es un gasto** / **Es una pregunta**. Funciona igual con texto y con audio.

### Cómo se responde (sin que la IA escriba SQL)
- Usá **tool use** de la API de Claude: la IA recibe la pregunta y un conjunto fijo de herramientas implementadas en Java, elige cuál llamar y con qué parámetros, Java ejecuta la consulta, y la IA redacta la respuesta con el resultado.
- **La IA nunca genera SQL.** Solo puede usar estas herramientas:
  - `totalGastado(desde, hasta, categoria?, medioPago?, cuenta?, comercio?)`
  - `gastoPorCategoria(desde, hasta)`
  - `compararPeriodos(desdeA, hastaA, desdeB, hastaB)`
  - `listarMovimientos(desde, hasta, filtros, limite)` (con un tope de 20 resultados)
  - `buscarProducto(texto, desde, hasta)` (sobre el detalle de los tickets: cantidad y gasto total)
  - `ingresosYDisponible(mes)`
- Las fechas relativas ("este mes", "la semana pasada", "desde septiembre") las resuelve Java: pasale a la IA la fecha de hoy, y validá que lo que devuelve sean fechas reales y que `desde` no sea posterior a `hasta`.
- Un máximo de 3 llamadas a herramientas por pregunta, para acotar el costo.
- Aplicá el mismo control de números inventados que en los resúmenes, contra los resultados de las herramientas.
- Si la pregunta no se puede responder con las herramientas, que lo diga ("todavía no sé responder eso") en lugar de inventar.
- Registrá en `uso_ia` con propósito `CONSULTA`, sumando todas las llamadas de esa pregunta.

## Tests

Sin Docker ni API real:
- Límites de semana (lunes a domingo) y de mes en la zona argentina, incluido el envío pendiente al arrancar.
- Los cálculos: gastos hormiga, días sin gastos, productos más comprados, comparación hasta el mismo punto.
- El control de números inventados: texto válido, texto con un número que no está, redondeos.
- La clasificación de intención: gasto, pregunta y caso dudoso.
- Cada herramienta contra datos en H2, y el flujo de tool use con la IA simulada (incluido el tope de 3 llamadas y fechas inválidas).

## Al terminar

Corré los tests, levantá la aplicación con el perfil `postgres` y decime cómo probar: `/resumen`, `/resumen mes`, tres preguntas de la lista (una por audio) y cómo forzar el resumen semanal sin esperar al domingo. Contame cuánto costó cada resumen y cada pregunta según `uso_ia`. Commits chicos y al final subí todo a GitHub.
