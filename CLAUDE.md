# Gastito

Bot de Telegram personal para registrar y analizar gastos e ingresos. Lo usa una sola persona (el dueño del repo). La carga diaria se hace por mensajes de texto, audios y fotos de tickets; la IA (Claude Haiku por API) interpreta lo que manda el usuario y el sistema guarda movimientos en una base de datos.

Este archivo es el contexto permanente del proyecto. Leelo antes de cualquier tarea.

## Cómo trabajar (ahorro de tokens)

- Durante el desarrollo, corré solo los tests de las clases que tocás (`./mvnw -q test -Dtest=...`). La suite completa, una vez por parte, y reportá solo el resumen (cuántos pasan/fallan; el detalle de qué falló solo si falla algo).
- No releas archivos grandes enteros: buscá el método y leé solo ese tramo. Si una clase pasa de ~500 líneas, proponé dividirla.
- La documentación (README y este archivo) se actualiza al terminar cada **etapa**, no cada parte; este archivo se mantiene corto, lo detallado va en `docs/`.
- Los reportes de cierre de cada parte: máximo 10 líneas (qué se hizo, cómo probarlo, decisiones tomadas).
- Antes de pausar o cambiar de parte, verificá que todo esté commiteado y subido.

## Stack

- Java 25, Spring Boot 3 (Maven).
- Spring Data JPA + Flyway para la base de datos.
- Base de datos:
  - Perfil `dev` (por defecto): H2 en archivo (`./data/gastito`), en modo compatibilidad PostgreSQL. No necesita Docker.
  - Perfil `prueba`: la base `gastito_prueba`, en el mismo PostgreSQL de la VM (mismo usuario del `.env`; URL en `DB_PRUEBA_URL` o `localhost:5432/gastito_prueba`). Fotos de tickets en `data/prueba/`. **Es el perfil con el que arranca `iniciar.bat` hasta el lanzamiento.**
  - Perfil `postgres`: la base real, `gastito`. Está vacía y reservada para cuando se empiece a usar de verdad: no cargues datos ahí ni corras nada contra ella sin que se pida. El día del lanzamiento se cambia el perfil por defecto de `scripts/iniciar.ps1` a `postgres` (ver README).
  - Las migraciones de Flyway tienen que funcionar en todos.
- Hasta terminar todas las etapas (hasta la 8) la aplicación no se usa con datos reales: todo es prueba. Los datos de prueba se regeneran desde cero con `generar-datos-prueba.bat` (`ar.gastito.prueba`, solo con el perfil `prueba`; antes de borrar comprueba que la base sea `gastito_prueba`).
- Telegram: librería TelegramBots (rubenlagus), modo long polling. No se usan webhooks: el bot corre en la PC del usuario y no tiene URL pública.
- IA: SDK oficial de Anthropic para Java (`com.anthropic:anthropic-java`). Modelo por defecto: `claude-haiku-5-5`.
- Verificá en Maven Central la última versión estable de cada dependencia antes de agregarla. No inventes versiones.

## Secretos y configuración

- Los secretos viven en un archivo `.env` en la raíz del proyecto (ya existe, no lo crees ni lo pises). Variables:
  - `TELEGRAM_BOT_TOKEN`
  - `TELEGRAM_BOT_USERNAME`
  - `TELEGRAM_ALLOWED_USER_ID`
  - `ANTHROPIC_API_KEY`
  - `WHISPER_URL` y `WHISPER_API_KEY` (servidor de Whisper para los audios)
  - Opcionales: `SERVER_ADDRESS` y `GASTITO_DASHBOARD_TOKEN`, juntas, para abrir el dashboard en la red. Por defecto el servidor escucha solo en `127.0.0.1`, y sin token no arranca escuchando en otra dirección.
- Spring los lee con `spring.config.import=optional:file:.env[.properties]` en `application.yml`.
- `.env` y `data/` tienen que estar en `.gitignore`. Nunca subas secretos al repo, nunca los imprimas en logs ni en mensajes de error.
- Si no existe un `.env.example`, crealo con las variables vacías.

## Reglas de negocio importantes

1. **Seguridad del bot:** el bot ignora en silencio cualquier mensaje cuyo `from.id` no sea `TELEGRAM_ALLOWED_USER_ID`. Los bots de Telegram son públicos.
2. **La IA interpreta, no calcula.** Totales, sumas y comparaciones se calculan con SQL o Java sobre la base. La IA solo extrae datos de lo que manda el usuario (monto, descripción, categoría, medio de pago) y redacta respuestas.
3. **Confirmar antes de guardar.** Todo movimiento interpretado se muestra al usuario con botones (Guardar / Editar / Cancelar) antes de persistirse.
4. **Plata siempre en `BigDecimal`**, moneda ARS por defecto. Nunca `double`.
5. **Tipos de movimiento** (una sola tabla `movimiento` con un campo `tipo`):
   - `GASTO`: cuenta como gasto.
   - `INGRESO`: cuenta como ingreso. Lleva una `clase_ingreso` fija: `SUELDO`, `AGUINALDO` (va aparte para no inflar la comparación del sueldo entre meses) o `EXTRA` (trabajo particular, venta, regalo…, con descripción). No lleva categoría.
   - `DEVOLUCION`: alguien devuelve plata (por ejemplo su parte de una juntada). No cuenta como ingreso.
   - `PAGO_TARJETA`: pago del resumen de la tarjeta. No cuenta como gasto, porque los consumos ya se contaron al comprar.
   - `ENTRE_CUENTAS`: pasar plata entre cuentas propias. No cuenta.
6. **Juntada** es una marca booleana sobre un movimiento, no una categoría.
7. **Registrar el uso de la IA:** cada llamada a la API guarda en la tabla `uso_ia` la fecha, el modelo, los tokens de entrada y salida (vienen en la respuesta), el propósito (`TEXTO`, `AUDIO`, `TICKET`, `RECIBO`, `RESUMEN`, `CONSULTA`) y el costo en USD, calculado al guardar y fijo (si cambian los precios, el historial no se recalcula). Los precios por millón de tokens van en `application.yml`, no en el código. Precio actual de `claude-haiku-5-5`: USD 0,10 de entrada y USD 0,50 de salida por millón de tokens (prompts de hasta 100.000 tokens).
8. **Las instrucciones (prompts) que se le mandan a la IA** van en archivos de texto en `src/main/resources/prompts/`, no hardcodeadas en Java, para poder ajustarlas sin tocar código.

## Datos iniciales (seed en la migración)

**Categorías:** Kiosco, Supermercado, Salidas, Comida afuera / delivery, Productos para la casa, Servicios (luz, gas, servicios sociales), Transporte / Moto, Suscripciones, Seguros, Salud / farmacia, Ropa, Impuestos y cargos de tarjeta, Otros.

**Formas de pago:** Efectivo, Débito, Tarjeta de crédito, Transferencia, QR.

**Cuentas:** Galicia, Mercado Pago. (El efectivo no tiene cuenta.)

**Tarjeta de crédito:** MasterCard Gold de Galicia. El resumen se paga por débito automático desde la caja de ahorro de Galicia.

**Diccionario de comercios** (tabla `alias_comercio`, para reconocer nombres raros del resumen de la tarjeta; se usa desde la etapa de resúmenes, pero la tabla y el seed van desde el principio):
- `FEDERA8` → Seguro, categoría Seguros, gasto fijo.
- `DIA TIENDA` → Supermercado Día, categoría Supermercado.
- `IMPUESTO DE SELLOS` → categoría Impuestos y cargos de tarjeta.
- Al comparar, ignorar números de sucursal o de póliza pegados al nombre (`DIA TIENDA 5601` = `DIA TIENDA`; `FEDERA8//4017901480026` = `FEDERA8`).

## Estructura y convenciones

- Paquete base: `ar.gastito`. Organización por funcionalidad (`bot`, `interpretacion`, `ia`, `audio`, `ticket`, `resumen`, `consulta`, `juntada`, `fijo`, `catalogo`, `movimiento`, `dashboard`, `prueba`, `config`), no por capa técnica. Las entidades de referencia (categorías, medios de pago, cuentas, tarjeta, alias de comercios) van en `catalogo`; `movimiento` es solo el movimiento.
- Compilar y testear con el Maven Wrapper (`./mvnw`, o `mvnw.cmd` en Windows).
- Los pedidos de cada etapa van en `docs/pedidos/`.
- **Dashboard web** (`ar.gastito.dashboard` + `src/main/resources/static/`): API REST bajo `/api` y una página en HTML, CSS y JS sin framework, siguiendo `docs/prototipo-dashboard.html`. El JavaScript no calcula nada: los totales, promedios y porcentajes llegan hechos de la API (la misma API va a servir para la Mini App). Plata en el JSON como número con 2 decimales; el formato argentino lo hace el frontend. Chart.js por WebJar, nunca desde un CDN.
- **Tickets** (`ar.gastito.ticket`): la foto se achica a 1568 px y se pasa a JPEG antes de mandarla a Haiku. El monto es el total impreso y sin total no se guarda nada. Las cuentas se controlan en Java (`ControlesTicket`, tolerancia $10): productos = subtotal, descuentos = descuentos impresos y subtotal + descuentos = total; la tarjeta dice cuál no cierra y qué línea no se pudo leer. Los importes se aceptan con coma o punto decimal en el mismo ticket (`Importes`). Los productos van en `movimiento_item` en el orden impreso (descuentos con subtotal negativo, debajo de su producto), con la descripción legible y la original y el código de barras si es un EAN-8/EAN-13 válido. La sucursal queda en el movimiento (para cruzarlo con el resumen de la tarjeta en la etapa 7). La foto va en `data/tickets/AAAA/MM/<id>.jpg`, que se borra si el ticket se cancela. Privacidad: la respuesta de la IA solo tiene campos para lo que se guarda (nada de tarjeta, CUIT, caja, cajero ni número de cliente); en los textos libres Java borra además tarjetas y CUIT, pero no números por su largo. Caso de prueba real: `src/test/resources/tickets/dia-2026-09-22-esperado.json`.
- **Resúmenes y preguntas** (`ar.gastito.resumen`, `ar.gastito.consulta`): Java arma los datos (JSON) y la IA solo redacta. `ControlNumeros` compara cada número del texto de la IA con los del JSON o los resultados de las herramientas; si sobra uno, el texto se descarta y quedan los números de Java. Las preguntas usan tool use con 7 herramientas SQL (la 7.ª, `recibosSueldo`, sale de `recibo.AnalisisRecibos`), como mucho 3 llamadas, y fechas de una tabla de rangos calculada en Java o explícitas validadas en Java. Los envíos automáticos quedan en `resumen_envio` (si la PC estuvo apagada, solo el más reciente de cada tipo y "Ver anteriores").
- **Compras repetidas** (`movimiento.RepetidosService`, `ReglaRepetidos`, `Huella`): un gasto nuevo se compara con los confirmados de ±3 h (no con las cuotas futuras) y con los cobros pendientes de fijos del mes. Mismo monto (1% o $50), y misma categoría o comercio si los dos los tienen. En una juntada la huella usa `juntada.total_pagado`. Al unir queda el que ya estaba, con lo mejor de los dos, y la unión se anota en `movimiento_union`. La misma huella es la que se va a usar con los avisos y el resumen de la tarjeta (etapa 7).
- **Juntadas** (`ar.gastito.juntada`): la IA extrae el total y las personas (o los nombres) en la misma llamada que el gasto; divide Java (`DivisionJuntada`: los demás a peso para abajo, el redondeo es mío; las partes cambiadas a mano o ya pagadas quedan fijas y el resto se reparte para que el total cierre). El movimiento es mi parte (lo que cuenta como gasto); `juntada` guarda el total y desde dónde salió. Las devoluciones son `DEVOLUCION` (no ingresos) y marcan pagada a la persona; dar por perdida una parte crea un gasto con la categoría y la fecha originales. En el bot: `ConversacionJuntadas`, y `Tarjetas` muestra la juntada cuando el movimiento tiene la marca.
- **Gastos fijos** (`ar.gastito.fijo`): `GastoFijo` (mensual o bimestral desde `mes_inicio`; si el mes no tiene el día, el último). `GastoFijoService.generarCobros` crea el cobro pendiente el día que toca y los que faltaron con la PC apagada, uno por fijo y por mes (`movimiento.fijo_periodo`, también si se canceló con "este mes no"). Los variables proponen el promedio de los últimos 3 cobros; si un cobro difiere más de un 5% del anterior, se avisa. El bot pregunta con `FijosProgramados` (al arrancar y cada 10 minutos) y `ConversacionFijos`; el alta es con `/fijo` (`ParserFijo`, sin IA) o con la intención `FIJO`. Para la etapa 7: `confirmarDesdeResumen`.
- **Recibos de sueldo** (`ar.gastito.recibo`): el PDF con texto se pasa por PDFBox (`LectorPdf`, sin CUIL/CBU/legajo) y a Haiku va solo el texto; un escaneo o una foto va como imagen. La IA devuelve el tipo de documento en la misma llamada (`leer-recibo.txt`); una foto sin texto que Haiku reconoce como recibo en el flujo de tickets se vuelve a leer como recibo. Java valida (`ValidadorRecibo`: remunerativo + no remunerativo − descuentos = neto, tolerancia $1; sin neto no se guarda). El recibo es un `INGRESO` pendiente por el neto (origen `RECIBO`, en el mes del período) más `recibo_sueldo`/`recibo_concepto`; si el mes ya tenía sueldo o aguinaldo cargado, al guardar pregunta si lo reemplaza. El archivo va en `data/recibos/AAAA/MM/` y se borra si se cancela. Un recibo no cuenta como inicio del registro (`InicioRegistro`). Los tests usan recibos inventados.
- **Costos de la IA** (`ia.CostosIaService`, `ia.TopeIaService`): todo sale de `uso_ia`, en USD con 6 decimales. Proyección = gastado ÷ días transcurridos × días del mes. `gastito.ia.tope-mensual-usd` (2 por defecto) solo avisa: una vez al 80 % y otra al 100 % por mes (`aviso_tope_ia`, se anota recién cuando Telegram recibe el aviso). Lo usan `/costos` y la sección Costos IA del dashboard.
- **Sin datos no es $0** (`movimiento.InicioRegistro`): un período que empieza antes del primer movimiento confirmado no tiene datos. No se compara contra él (resúmenes, dashboard y preguntas lo dicen así) ni se calculan ahorros con él, y ningún escenario de ahorro promete más de lo gastado en la categoría en el período.
- Las billeteras del dashboard salen de los datos (la tarjeta o la cuenta del movimiento, o `medio_pago.es_efectivo`), nunca de nombres fijos en el código.
- Nombres del dominio en español (`Movimiento`, `Categoria`, `MedioPago`), código y comentarios técnicos pueden ir en inglés o español pero consistentes.
- Tests con JUnit 5. Todo parser o regla de negocio nueva lleva tests.
- Commits chicos y descriptivos, uno por paso lógico.

## Hoja de ruta

1. Etapa 1 (hecha): bot funcionando con mensajes de texto, interpretación con Haiku, confirmación y guardado.
2. Etapa 2 (hecha): audios (Whisper local + mismo flujo que texto).
3. Etapa 3 (hecha): fotos de tickets (visión de Haiku, detalle por producto). Ver `docs/pedidos/etapa-3.md`.
   - Hecho fuera de orden: el dashboard web (en `localhost`, base de la etapa 5) y la carga de ingresos (sueldo, aguinaldo y extras, de la etapa 6), por bot y por dashboard. Ver `docs/pedidos/dashboard.md`.
4. Etapa 4 (hecha): resúmenes semanales y mensuales y consultas en lenguaje natural. Ver `docs/pedidos/etapa-4.md`.
5. **Etapa 5 (actual):** panel web dentro de Telegram (Mini App).
6. Etapa 6: compras repetidas, juntadas, ingresos, gastos fijos.
7. Etapa 7: resumen de la tarjeta (PDF con texto, se parsea con PDFBox sin IA) y avisos automáticos de Galicia y Mercado Pago.
8. Etapa 8 (hecha): recibos de sueldo y pantalla de costos de la IA. Ver `docs/pedidos/etapa-8.md`. La etapa 7 sigue postergada.

No implementes funcionalidades de etapas futuras salvo que se pida, pero diseñá el modelo de datos para que no haya que rehacerlo.
