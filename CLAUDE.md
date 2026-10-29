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
  - Perfil `postgres` (**el de `iniciar.bat`**): la base real, `gastito`, con **datos reales** desde el lanzamiento (28/10/2026). No cargues, borres ni corras nada contra ella sin que se pida (el backup y su prueba de restauración en otra base, sí).
  - Perfil `prueba` (`iniciar.bat prueba`): la base `gastito_prueba`, en el mismo PostgreSQL de la VM (mismo usuario del `.env`; URL en `DB_PRUEBA_URL` o `localhost:5432/gastito_prueba`), **solo para probar cambios**. Fotos de tickets en `data/prueba/`.
  - Las migraciones de Flyway tienen que funcionar en todos, y nunca rompen datos existentes de la base real.
- Al arrancar, `bot.AvisoArranque` dice en la consola y por Telegram con qué base corre. Los datos de prueba se regeneran con `generar-datos-prueba.bat` (`ar.gastito.prueba`, solo con el perfil `prueba`; antes de borrar comprueba que la base sea `gastito_prueba`).
- Backup: `backup.bat` → `scripts/backup.ps1` (a `BACKUP_DIR`, idealmente sincronizada con la nube; pg_dump por `ssh -4 devbox` + `data/`, últimos 30) y `scripts/restaurar.ps1` (en una base nueva, nunca sobre `gastito`). Arranque automático y backup diario con el Programador de tareas: ver README.
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
  - `DB_URL`, `DB_USER`, `DB_PASSWORD` (y opcional `DB_PRUEBA_URL`); opcional `BACKUP_DIR`.
  - Opcionales: `SERVER_ADDRESS` y `GASTITO_DASHBOARD_TOKEN`, juntas, para abrir el dashboard en la red. Por defecto el servidor escucha solo en `127.0.0.1`, y sin token no arranca escuchando en otra dirección.
- Spring los lee con `spring.config.import=optional:file:.env[.properties]` en `application.yml`.
- `.env` y `data/` tienen que estar en `.gitignore`. Nunca subas secretos al repo, nunca los imprimas en logs ni en mensajes de error.
- Si no existe un `.env.example`, crealo con las variables vacías.

## Reglas de negocio importantes

1. **Seguridad del bot:** el bot ignora en silencio cualquier mensaje cuyo `from.id` no sea `TELEGRAM_ALLOWED_USER_ID`. Los bots de Telegram son públicos.
2. **La IA interpreta, no calcula.** Totales, sumas y comparaciones se calculan con SQL o Java sobre la base. La IA solo extrae datos de lo que manda el usuario (monto, descripción, categoría, medio de pago) y redacta respuestas.
3. **Los gastos claros se guardan solos y se pueden deshacer o editar; se pide confirmación solo cuando hay una duda real** (`bot.GuardadoAutomatico`). Se guarda solo un gasto simple de texto, audio o ticket con monto claro y categoría de confianza alta: tarjeta "✅ Guardado" con ✏️ Editar y ↩️ Deshacer (borra el movimiento, en cualquier momento), y si no se dijo el medio de pago, una fila para elegirlo con un toque. Se confirma con Guardar / Editar / Cancelar: categoría dudosa, compra repetida, juntada, cuotas, fijo, ingreso, varios con alguno dudoso, ticket que no cierra o que dice solo "TARJETA", y más de `gastito.bot.monto-maximo-automatico` ($100.000). Con `gastito.bot.guardado-automatico: false`, todo se confirma.
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
9. **Lenguaje natural para todo.** Todo lo que se puede hacer con un comando se puede pedir con un mensaje o un audio, dicho como sea; los comandos (`/fijo`, `/sueldo`, `/calcular`…) quedan como atajos y, si no entienden los argumentos, los interpreta la IA. El bot **nunca** le contesta "escribilo con este formato": si entiende, muestra la tarjeta de confirmación; si falta un dato, lo pregunta con botones (con "No sé" donde tenga sentido); si no está claro qué quiere, pregunta con botones las 2 o 3 opciones más probables.

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
- **Dashboard web** (`ar.gastito.dashboard` + `src/main/resources/static/`): Íconos SVG de Lucide en `static/img/icons.svg` (sin emojis en la web; `categoria.icono`, el emoji es del bot), `iconos.js` y `movil.js` (<720 px: navegación abajo y tablas como tarjetas). API REST bajo `/api` y una página en HTML, CSS y JS sin framework, siguiendo `docs/prototipo-dashboard.html`. El JavaScript no calcula nada: los totales, promedios y porcentajes llegan hechos de la API (la misma API va a servir para la Mini App). Plata en el JSON como número con 2 decimales; el formato argentino lo hace el frontend. Chart.js por WebJar, nunca desde un CDN.
- **Interpretación** (`ar.gastito.interpretacion`): una sola llamada a Haiku (`interpretar-gasto.txt`) reconoce todas las `Intencion`es (gasto, ingreso, devolución, alta y baja de fijo, corregir/borrar lo último, cálculo de sueldo, cancelar cuotas, dar por perdida una deuda, ver una lista, pregunta, dudoso con `opciones`), tolerando errores de Whisper. El schema lleva descripciones mínimas: lo explica el prompt. Elegir una opción de un dudoso revalida la misma respuesta (`reinterpretar`), sin otra llamada. Los pedidos de listas obvios ("¿qué fijos me faltan?") los reconoce Java sin IA (`ClasificadorIntencion.pedidoDeLista`) y van al mismo código que el comando. Lo que cambia algo ya cargado (`bot.PedidosNaturales`) siempre se confirma con un botón.
- **Tickets** (`ar.gastito.ticket`): la foto se achica a 1568 px y se pasa a JPEG antes de mandarla a Haiku. El monto es el total impreso y sin total no se guarda nada. Las cuentas se controlan en Java (`ControlesTicket`, tolerancia $10): productos = subtotal, descuentos = descuentos impresos y subtotal + descuentos = total; la tarjeta dice cuál no cierra y qué línea no se pudo leer. Los importes se aceptan con coma o punto decimal en el mismo ticket (`Importes`). Los productos van en `movimiento_item` en el orden impreso (descuentos con subtotal negativo, debajo de su producto), con la descripción legible y la original y el código de barras si es un EAN-8/EAN-13 válido. La sucursal queda en el movimiento (para cruzarlo con el resumen de la tarjeta en la etapa 7). La foto va en `data/tickets/AAAA/MM/<id>.jpg`, que se borra si el ticket se cancela. Privacidad: la respuesta de la IA solo tiene campos para lo que se guarda (nada de tarjeta, CUIT, caja, cajero ni número de cliente); en los textos libres Java borra además tarjetas y CUIT, pero no números por su largo. Caso de prueba real: `src/test/resources/tickets/dia-2026-09-22-esperado.json`.
- **Resúmenes y preguntas** (`ar.gastito.resumen`, `ar.gastito.consulta`): Java arma los datos (JSON) y la IA solo redacta. `ControlNumeros` compara cada número del texto de la IA con los del JSON o los resultados de las herramientas; si sobra uno, el texto se descarta y quedan los números de Java. Las preguntas usan tool use con 7 herramientas SQL (la 7.ª, `recibosSueldo`, sale de `recibo.AnalisisRecibos`), como mucho 3 llamadas, y fechas de una tabla de rangos calculada en Java o explícitas validadas en Java. Los envíos automáticos quedan en `resumen_envio` (si la PC estuvo apagada, solo el más reciente de cada tipo y "Ver anteriores").
- **Compras repetidas** (`movimiento.RepetidosService`, `ReglaRepetidos`, `Huella`): un gasto nuevo se compara con los confirmados de ±3 h (no con las cuotas futuras) y con los cobros pendientes de fijos del mes. Mismo monto (1% o $50), y misma categoría o comercio si los dos los tienen. En una juntada la huella usa `juntada.total_pagado`. Al unir queda el que ya estaba, con lo mejor de los dos, y la unión se anota en `movimiento_union`. La misma huella es la que se va a usar con los avisos y el resumen de la tarjeta (etapa 7).
- **Juntadas** (`ar.gastito.juntada`): la IA extrae el total y las personas (o los nombres) en la misma llamada que el gasto; divide Java (`DivisionJuntada`: los demás a peso para abajo, el redondeo es mío; las partes cambiadas a mano o ya pagadas quedan fijas y el resto se reparte para que el total cierre). El movimiento es mi parte (lo que cuenta como gasto); `juntada` guarda el total y desde dónde salió. Las devoluciones son `DEVOLUCION` (no ingresos) y marcan pagada a la persona; dar por perdida una parte crea un gasto con la categoría y la fecha originales. En el bot: `ConversacionJuntadas`, y `Tarjetas` muestra la juntada cuando el movimiento tiene la marca.
- **Gastos fijos** (`ar.gastito.fijo`): `GastoFijo` (mensual o bimestral desde `mes_inicio`; si el mes no tiene el día, el último). `GastoFijoService.generarCobros` crea el cobro pendiente el día que toca y los que faltaron con la PC apagada, uno por fijo y por mes (`movimiento.fijo_periodo`, también si se canceló con "este mes no"). Los variables proponen el promedio de los últimos 3 cobros; si un cobro difiere más de un 5% del anterior, se avisa. El bot pregunta con `FijosProgramados` (al arrancar y cada 10 minutos) y `ConversacionFijos`. El alta (`ConversacionAltaFijo`, intención `ALTA_FIJO` o el atajo `/fijo` con `ParserFijo`) pregunta de a un dato con botones; día y monto pueden quedar sin definir ("No sé": sin día no hay recordatorio ni cobro automático, sin monto no hay "Pagado" hasta el primer pago), la frase original queda como alias y avisa si ya hay uno parecido. Para la etapa 7: `confirmarDesdeResumen`.
- **Calculadora de sueldo** (`ar.gastito.sueldo`): tu sueldo real es calcular → cobrar (`/sueldo`) → recibo una semana después. Conceptos configurables (`concepto_sueldo`: `REMUNERATIVO_FIJO`, `HORAS` con factor fracción 3/2·2/1·8/60, `NO_REMUNERATIVO`, `DESCUENTO_PORCENTAJE`) y escalas con vigencia (`escala_sueldo`/`escala_valor`; mes → la escala con "desde" más reciente que no lo pase; se editan en el dashboard, nada de montos en Java ni en la migración). `CalculadoraSueldo` (BigDecimal, centavos por concepto): bruto = fijos + horas; descuentos = bruto × %; **neto total = neto de haberes + no remunerativos**. El cálculo del mes se guarda con sus líneas (`calculo_sueldo`) y no cambia si cambia la escala. Bot: `/calcular 10 6 30` o "este mes hice 10 horas al 50…" (`ParserHorasSueldo`, sin IA) → tarjeta con Guardar/Editar/Cancelar (las horas viajan en el botón), `/calculo`. `SueldoService.comparar`: el sueldo cobrado contra el cálculo, ⚠️ si pasa `gastito.sueldo.tolerancia-cobro-porcentaje` (1 %). Cada sueldo/aguinaldo guarda su fecha de cobro (cuenta para el disponible de ese mes) y su **período** (mes trabajado, `movimiento.periodo`; sueldo cobrado hasta el día 15 = mes anterior, configurable), que vincula cálculo y recibo; se cambia con un botón en la tarjeta. `InicioRegistro` toma el primer **gasto**, así los meses con solo ingresos salen "sin datos".
- **Recibos de sueldo** (`ar.gastito.recibo`) son un **control, no un registro**: no crean ingreso. El PDF con texto se pasa por PDFBox (`LectorPdf`, sin CUIL/CBU/legajo) y a Haiku va solo el texto; un escaneo o una foto va como imagen. La IA devuelve el tipo de documento en la misma llamada (`leer-recibo.txt`); una foto sin texto que Haiku reconoce como recibo en el flujo de tickets se vuelve a leer como recibo. Java valida (`ValidadorRecibo`: remunerativo + no remunerativo − descuentos = neto, tolerancia $1; sin neto no se guarda). Se guarda directo (botón "Descartar") y se vincula al sueldo/aguinaldo cobrado de su período (por `movimiento.periodo` o a mano con `movimiento_id`; si el cobro llega después, se vincula solo). Otro recibo del mismo mes y tipo lo reemplaza. `ControlRecibo` (Java) busca lo raro: neto ≠ cobrado, cada concepto y cada % de descuento contra el cálculo, conceptos que faltan o aparecen contra el mes anterior (lo que el cálculo explica no cuenta), totales que no cierran. La IA solo mapea cada renglón a un concepto del sueldo (`MapeadorConceptos`; coincidencia por nombre primero, lo respondido se guarda en `mapeo_concepto_recibo`). `SeguimientoSueldo`: mes por mes calculado · cobrado · recibo (dashboard y herramienta `recibosSueldo`). El archivo va en `data/recibos/AAAA/MM/` y se borra al descartar. Los tests usan recibos inventados.
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
8. Etapa 8 (hecha): recibos de sueldo y pantalla de costos de la IA. Ver `docs/pedidos/etapa-8.md`; la Parte A cambió a calculadora de sueldo + recibo como control (`docs/pedidos/etapa-8-sueldo.md`). La etapa 7 sigue postergada.

No implementes funcionalidades de etapas futuras salvo que se pida, pero diseñá el modelo de datos para que no haya que rehacerlo.
