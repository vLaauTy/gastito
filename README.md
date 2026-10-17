# Gastito

Bot de Telegram personal para registrar gastos. Le escribís algo como `kiosco 1500` o
`3700 en pan de pancho y papas, efectivo`, te muestra lo que entendió y lo guardás con un botón.

Cómo se interpreta un mensaje, de lo más barato a lo más caro:

1. **Parser simple, sin IA:** `kiosco 1500`, `1500 super débito`.
2. **Historial:** si ya guardaste gastos con una descripción igual o muy parecida y siempre en la misma
   categoría, se usa esa sin llamar a la IA.
3. **Claude Haiku:** devuelve hasta 3 categorías posibles y qué tan seguro está. Si no está seguro, el bot te
   pregunta con botones.

Todos los totales se calculan con SQL sobre la base.

## Requisitos

- Java 25 o más nuevo.
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

## Usar el bot

Cada gasto es **una tarjeta** que se edita en el mismo mensaje:

- Si la categoría es clara, la tarjeta viene con **✅ Guardar**, **✏️ Editar** y **❌ Cancelar**.
- Si es dudosa, muestra **¿Qué categoría?** con un botón por cada candidata y **Otra…** (todas las
  categorías). Guardar aparece cuando hay categoría y monto.
- **✏️ Editar** abre un botón por campo. Categoría y medio de pago se eligen con botones. Monto, fecha y
  descripción se escriben: la tarjeta se actualiza y el mensaje con el valor se borra, así el chat queda limpio.
- Al guardar, la tarjeta pasa a **✅ Guardado** con el total del mes en esa categoría.

Lo que guardás le enseña al bot: la próxima vez que cargues algo parecido usa la misma categoría, y tus últimas
20 confirmaciones van como ejemplos en el prompt de la IA.

Abajo del chat queda un menú fijo con estos comandos, que también están en el botón Menú de Telegram:

| Botón          | Comando   | Qué hace                                         |
|----------------|-----------|--------------------------------------------------|
|                | `/start`  | Saludo, ejemplos y el menú                       |
| 📊 Hoy         | `/hoy`    | Gastos de hoy y el total                         |
| 📅 Mes         | `/mes`    | Total del mes y barras por categoría             |
| 💸 Costos IA   | `/costos` | Lo gastado en IA este mes, en dólares            |
| ❓ Ayuda       | `/ayuda`  | Cómo cargar gastos                               |

Las categorías, sus descripciones (las que lee la IA), sus emojis y las palabras clave del parser simple están en
la tabla `categoria` y se pueden cambiar en la base sin tocar código.

## Pasar a PostgreSQL (perfil `postgres`)

1. Tené un PostgreSQL accesible (por ejemplo en Docker dentro de una VM, publicado en `localhost:5432`) y
   agregá la conexión al `.env`:

   ```properties
   DB_URL=jdbc:postgresql://localhost:5432/gastito
   DB_USER=gastito
   DB_PASSWORD=...
   ```

   La aplicación no tiene valores por defecto para estas tres: si falta alguna, no arranca. La contraseña
   vive solo en el `.env`.

   Si preferís levantar la base con el `docker-compose.yml` del repo (`docker compose up -d`), usa las mismas
   `DB_USER` y `DB_PASSWORD` del `.env`.

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
