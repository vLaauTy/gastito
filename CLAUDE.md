# Gastito

Bot de Telegram personal para registrar y analizar gastos e ingresos. Lo usa una sola persona (el dueño del repo). La carga diaria se hace por mensajes de texto, audios y fotos de tickets; la IA (Claude Haiku por API) interpreta lo que manda el usuario y el sistema guarda movimientos en una base de datos.

Este archivo es el contexto permanente del proyecto. Leelo antes de cualquier tarea.

## Stack

- Java 25, Spring Boot 3 (Maven).
- Spring Data JPA + Flyway para la base de datos.
- Base de datos:
  - Perfil `dev` (por defecto): H2 en archivo (`./data/gastito`), en modo compatibilidad PostgreSQL. No necesita Docker.
  - Perfil `postgres`: PostgreSQL levantado con `docker-compose.yml`.
  - Las migraciones de Flyway tienen que funcionar en los dos.
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
   - `INGRESO`: sueldo o trabajo particular. Cuenta como ingreso.
   - `DEVOLUCION`: alguien devuelve plata (por ejemplo su parte de una juntada). No cuenta como ingreso.
   - `PAGO_TARJETA`: pago del resumen de la tarjeta. No cuenta como gasto, porque los consumos ya se contaron al comprar.
   - `ENTRE_CUENTAS`: pasar plata entre cuentas propias. No cuenta.
6. **Juntada** es una marca booleana sobre un movimiento, no una categoría.
7. **Registrar el uso de la IA:** cada llamada a la API guarda en la tabla `uso_ia` la fecha, el modelo, los tokens de entrada y salida (vienen en la respuesta), el propósito (`TEXTO`, `AUDIO`, `TICKET`, `RESUMEN`, `CONSULTA`) y el costo en USD. Los precios por millón de tokens van en `application.yml`, no en el código. Precio actual de `claude-haiku-5-5`: USD 0,10 de entrada y USD 0,50 de salida por millón de tokens (prompts de hasta 100.000 tokens).
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

- Paquete base: `ar.gastito`. Organización por funcionalidad (`bot`, `interpretacion`, `ia`, `audio`, `catalogo`, `movimiento`, `config`), no por capa técnica. Las entidades de referencia (categorías, medios de pago, cuentas, tarjeta, alias de comercios) van en `catalogo`; `movimiento` es solo el movimiento.
- Compilar y testear con el Maven Wrapper (`./mvnw`, o `mvnw.cmd` en Windows).
- Los pedidos de cada etapa van en `docs/pedidos/`.
- Nombres del dominio en español (`Movimiento`, `Categoria`, `MedioPago`), código y comentarios técnicos pueden ir en inglés o español pero consistentes.
- Tests con JUnit 5. Todo parser o regla de negocio nueva lleva tests.
- Commits chicos y descriptivos, uno por paso lógico.

## Hoja de ruta

1. Etapa 1 (hecha): bot funcionando con mensajes de texto, interpretación con Haiku, confirmación y guardado.
2. **Etapa 2 (actual):** audios (Whisper local + mismo flujo que texto).
3. Etapa 3: fotos de tickets (visión de Haiku, detalle por producto).
4. Etapa 4: resúmenes semanales y consultas en lenguaje natural.
5. Etapa 5: panel web dentro de Telegram (Mini App).
6. Etapa 6: compras repetidas, juntadas, ingresos, gastos fijos.
7. Etapa 7: resumen de la tarjeta (PDF con texto, se parsea con PDFBox sin IA) y avisos automáticos de Galicia y Mercado Pago.
8. Más adelante: recibos de sueldo, pantalla de costos de la IA.

No implementes funcionalidades de etapas futuras salvo que se pida, pero diseñá el modelo de datos para que no haya que rehacerlo.
