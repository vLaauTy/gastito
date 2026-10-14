# Gastito

Bot de Telegram personal para registrar gastos. Le escribís algo como `kiosco 1500` o
`3700 en pan de pancho y papas, efectivo`, te muestra lo que entendió y lo guardás con un botón.

Los mensajes obvios (`kiosco 1500`) se interpretan sin IA. El resto lo interpreta Claude Haiku. Todos los
totales se calculan con SQL sobre la base.

## Requisitos

- Java 21 o más nuevo.
- Maven 3.9 o más nuevo.
- Un bot de Telegram creado con [@BotFather](https://t.me/BotFather).
- Una API key de Anthropic.

No hace falta Docker: por defecto la base es H2 en un archivo dentro del proyecto.

## Configuración

Los secretos van en un archivo `.env` en la raíz del proyecto. Nunca se sube al repo: está en el `.gitignore`.
Copiá `.env.example` y completalo:

```properties
TELEGRAM_BOT_TOKEN=123456:ABC...        # el token que te da @BotFather
TELEGRAM_BOT_USERNAME=mi_gastito_bot
TELEGRAM_ALLOWED_USER_ID=123456789      # tu id numérico de Telegram (te lo dice @userinfobot)
ANTHROPIC_API_KEY=sk-ant-...
```

El bot ignora en silencio los mensajes de cualquier usuario que no sea `TELEGRAM_ALLOWED_USER_ID`.

Otros ajustes, en `src/main/resources/application.yml`:

- `gastito.ia.modelo`: el modelo de Claude (por defecto `claude-haiku-5-5`).
- `gastito.ia.precios`: precio por millón de tokens de cada modelo, para calcular el costo en `/costos`.
- `gastito.zona-horaria`: por defecto `America/Argentina/Buenos_Aires`.

Las instrucciones que se le mandan a la IA están en `src/main/resources/prompts/` y se pueden ajustar sin
tocar código.

## Correr en perfil `dev` (H2, sin Docker)

```bash
mvn spring-boot:run
```

`dev` es el perfil por defecto. La base queda en `./data/gastito.mv.db`; Flyway crea las tablas y carga las
categorías, medios de pago y cuentas la primera vez. Para empezar de cero, frená la aplicación y borrá la
carpeta `data/`.

Cuando en el log aparece `Bot de Telegram conectado y escuchando mensajes`, abrí el chat con tu bot y mandá
`/start`.

Para generar un jar y correrlo sin Maven:

```bash
mvn package
java -jar target/gastito-0.1.0-SNAPSHOT.jar
```

Corré el jar desde la raíz del proyecto, porque ahí es donde busca el `.env` y la carpeta `data/`.

## Comandos del bot

| Comando   | Qué hace                                    |
|-----------|---------------------------------------------|
| `/start`  | Saludo y ejemplos                           |
| `/ayuda`  | Cómo cargar gastos                          |
| `/hoy`    | Gastos de hoy y el total                    |
| `/mes`    | Total del mes por categoría                 |
| `/costos` | Lo gastado en IA este mes, en dólares       |

## Pasar a PostgreSQL (perfil `postgres`)

Cuando tengas Docker funcionando:

1. Levantá la base:

   ```bash
   docker compose up -d
   ```

   Crea la base `gastito` con usuario y contraseña `gastito` en `localhost:5432`. Para cambiarlos, agregá
   `POSTGRES_DB`, `POSTGRES_USER` y `POSTGRES_PASSWORD` al `.env` (los leen tanto Docker Compose como la
   aplicación). También están `POSTGRES_HOST` y `POSTGRES_PORT`.

2. Corré la aplicación con el perfil `postgres`:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=postgres
   ```

   o con el jar:

   ```bash
   java -jar target/gastito-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres
   ```

Flyway crea las mismas tablas en PostgreSQL. Los datos que cargaste en H2 no se copian solos.

## Tests

```bash
mvn test
```

Usan H2 en memoria y no se conectan a Telegram ni a Anthropic.

Hay además un test que llama a la API real de Anthropic con la key del `.env`. Sirve para revisar el prompt
después de cambiarlo y cuesta fracciones de centavo. No corre con `mvn test`; se corre a mano:

```bash
mvn test -Dtest=InterpretacionRealIT -Dsurefire.failIfNoSpecifiedTests=false
```

## Estructura

```
ar.gastito
├── bot              Telegram: filtro de usuario, conversación, formato de mensajes
├── interpretacion   Parser simple, validación de la respuesta de la IA, fechas relativas
├── ia               Cliente de Claude, prompts, registro de tokens y costo (uso_ia)
├── movimiento       Entidades, repositorios y totales
└── config           Propiedades y reloj en hora de Argentina
```
