# Pedido para Claude Code: Dashboard web

Pegale a Claude Code todo lo que está debajo de la línea.

---

Leé `CLAUDE.md` antes de empezar. Vamos a hacer el **dashboard web**: una página para ver mis gastos con gráficos, servida por el mismo Spring Boot. El bot sigue siendo la forma de cargar; el dashboard es para mirar y analizar. Armá un plan y mostrámelo antes de tocar código.

## Decisiones de arquitectura

- **Backend:** una API REST en un paquete nuevo `ar.gastito.dashboard`, bajo `/api/...`, que devuelve JSON. Es de solo lectura, salvo la definición del presupuesto mensual.
- **Frontend:** HTML, CSS y JavaScript sin framework, en `src/main/resources/static/`. Para los gráficos usá **Chart.js**, guardado dentro del proyecto (por WebJar o copiando el archivo `.min.js` a `static/vendor/`), no desde un CDN: tiene que andar sin internet.
- La misma API va a servir más adelante para la PWA y la Mini App de Telegram (etapa 5), así que diseñala pensando en eso: nada de lógica de cálculo en el JavaScript.
- **Todos los totales se calculan en SQL o Java** (regla 2 de `CLAUDE.md`). El frontend solo muestra.
- Plata como `BigDecimal` en Java; en el JSON mandala como número con 2 decimales. El formato argentino (`$186.400`, `$1.500,50`) lo hace el frontend con `Intl.NumberFormat('es-AR')`.
- Meses según la zona `America/Argentina/Buenos_Aires`.

## Qué cuenta y qué no

- En los gastos solo entran movimientos **`CONFIRMADO`** de tipo **`GASTO`**. Los `PENDIENTE` y `CANCELADO` no, ni los `PAGO_TARJETA`, `ENTRE_CUENTAS` y `DEVOLUCION`.
- Ingresos, juntadas y gastos fijos todavía no existen (son de la etapa 6). En esas secciones mostrá un estado vacío prolijo ("Disponible cuando se agreguen los ingresos") y **no inventes datos**.

## Endpoints (todos aceptan `?mes=2026-10`; por defecto, el mes actual)

1. `GET /api/resumen`: total gastado en el mes, cantidad de gastos, comparación con el mes anterior **hasta el mismo día** (para que el 13 de octubre no se compare contra septiembre completo), promedio diario, y el presupuesto si está configurado.
2. `GET /api/gastos/por-dia`: un valor por cada día del mes, incluidos los días en cero.
3. `GET /api/gastos/por-categoria`: monto, cantidad y emoji por categoría, ordenado de mayor a menor.
4. `GET /api/gastos/por-billetera`: agrupado por cuenta (Galicia, Mercado Pago, y "Efectivo" para los que no tienen cuenta).
5. `GET /api/movimientos`: paginado, más nuevos primero, con filtros opcionales `categoria`, `medioPago`, `origen` y `q` (busca en la descripción, sin distinguir tildes ni mayúsculas).
6. `GET /api/movimientos.csv`: los movimientos del mes en CSV con separador `;` y codificación UTF-8 con BOM, para que Excel en castellano lo abra bien.
7. `GET /api/costos-ia`: costo del mes en USD, cantidad de llamadas y desglose por propósito, leído de `uso_ia`.

8. `GET /api/presupuesto?mes=2026-10` y `PUT /api/presupuesto/2026-10` (body con el monto): consultar y definir el presupuesto de un mes.

**Presupuesto mensual (varía mes a mes):**
- Se guarda en la base, en una tabla nueva `presupuesto` (migración nueva, no edites las anteriores) con el mes (`YYYY-MM`, único) y el monto.
- Si un mes no tiene presupuesto propio, **hereda el del último mes que sí tenga**, y la API indica de qué mes viene (`"heredadoDe": "2026-09"`), para que la tarjeta diga "igual que septiembre". Si nunca se definió ninguno, la tarjeta de presupuesto no aparece.
- Se puede definir de dos formas:
  - **Desde el bot:** `/presupuesto 250000` define el del mes actual; `/presupuesto 2026-11 280000` define el de otro mes; `/presupuesto` sin nada muestra el actual y cuánto va usado. Aceptá montos a la argentina (`250.000`, `250 mil`).
  - **Desde el dashboard:** en la tarjeta de presupuesto, un botón **Editar** que deja escribir el monto del mes que se está viendo. Es la única escritura que hace el dashboard.
- Tests: herencia del mes anterior, mes con presupuesto propio, sin ningún presupuesto, y el comando del bot con distintos formatos de monto.

## La página

Una sola página en `http://localhost:8080`, que tiene que verse bien en la pantalla de la PC y también en un celular angosto (las columnas se apilan):

- **Menú lateral** con Resumen y Movimientos (las demás secciones del prototipo, como Fijos, Juntadas e Ingresos, aparecen deshabilitadas con "Próximamente"). Abajo del menú, una cajita con el costo de la IA del mes.
- **Encabezado:** mes y año, botones para ir al mes anterior y al siguiente, y el botón **Exportar CSV**.
- **Cuatro tarjetas arriba:** gastado (con la comparación contra el mes anterior en verde si bajó y en naranja si subió), cantidad de gastos, promedio diario, y presupuesto usado con una barra (solo si está configurado).
- **Gasto por día:** gráfico de barras con una línea punteada en el promedio y el día más alto destacado.
- **Por categoría:** barras horizontales con emoji, nombre y monto.
- **Por billetera:** una barra horizontal repartida entre efectivo, Galicia y Mercado Pago, con referencias.
- **Últimos movimientos:** tabla con fecha, detalle, categoría, medio de pago, una etiqueta de origen (Texto / Audio) y monto. Tiene buscador y filtro por categoría, y en el celular se puede desplazar de costado.
- **Estilo:** fondo gris verdoso claro (`#F4F6F2`), tarjetas blancas con borde suave, verde `#1F7A4D` como color principal, y los montos en una fuente monoespaciada para que los números queden alineados. Que tenga **modo oscuro** automático según la configuración del sistema.
- Estados de carga y de error: si la API falla, un mensaje claro, no una pantalla en blanco. Si el mes no tiene gastos, un estado vacío que diga cómo cargar el primero desde el bot.

## Seguridad

- Por defecto el servidor escucha solo en `127.0.0.1` (`server.address`), así nadie más de mi Wi-Fi puede ver mis gastos.
- Dejá documentado en el README cómo abrirlo al celular en la misma red, pero protegido: una propiedad `gastito.dashboard.token` (leída del `.env`) que, si está definida, exija ese token para entrar. Sin token configurado, no se puede abrir a la red.

## Extra en el bot

Un comando `/dashboard` (y un botón en el menú fijo) que responda con el link `http://localhost:8080`, aclarando que se abre desde la PC.

## Tests

Sin Docker:
- De los cálculos del resumen con datos en H2: que excluyan pendientes, cancelados y tipos que no son gasto; los límites de mes en la zona horaria argentina; la comparación "hasta el mismo día", incluido el caso de un mes más corto que el anterior.
- De los endpoints con `@WebMvcTest` o `MockMvc`: formato del JSON, filtros y paginación de movimientos, y el CSV.
- Del token: sin token configurado no se puede abrir a la red; con token, sin token en el pedido, responde 401.

## Al terminar

Corré los tests, levantá la aplicación con el perfil `postgres` y decime cómo abrir el dashboard. Commits chicos por cada paso.

---

## Ajustes acordados antes de empezar

- **Puerto:** queda 8090 (el 8080 lo usa otro servicio en esta PC). El link sale de `server.port`.
- **Diseño:** se sigue `docs/prototipo-dashboard.html`, con las vistas Resumen, Movimientos e Ingresos.
- **Sin presupuesto:** el presupuesto del mes son los ingresos del mes, y eso ya lo muestra la tarjeta
  Disponible (ingresos menos gastos). No hay tabla, endpoint ni comando de presupuesto.
- **Ingresos (adelantados de la etapa 6):** `tipo = INGRESO` con una clase fija `SUELDO`, `AGUINALDO` o `EXTRA`.
  Se cargan desde el formulario del dashboard o con `/sueldo`, `/aguinaldo` e `/ingreso` en el bot. El
  aguinaldo se muestra separado del sueldo. El gráfico de ingresos muestra los últimos 6 meses.
- **Billeteras:** salen de los datos (la tarjeta o la cuenta de cada movimiento, o el medio de pago efectivo).
  Los gastos sin nada de eso van a "Sin especificar", en gris claro.
- **Lo que notó la IA, Fijos y Juntadas:** "Próximamente".
