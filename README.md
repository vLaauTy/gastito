# Gastito

Bot de Telegram personal para registrar gastos. Le escribís algo como `kiosco 1500` o
`3700 en pan de pancho y papas, efectivo` (o se lo decís en un audio), te muestra lo que entendió y lo guardás
con un botón.

Cómo se interpreta un mensaje, de lo más barato a lo más caro:

1. **Parser simple, sin IA:** `kiosco 1500`, `1500 super débito`.
2. **Historial:** si ya guardaste gastos con una descripción igual o muy parecida y siempre en la misma
   categoría, se usa esa sin llamar a la IA.
3. **Claude Haiku:** devuelve hasta 3 categorías posibles y qué tan seguro está. Si no está seguro, el bot te
   pregunta con botones.

Los audios se transcriben con un servidor de Whisper local y el texto sigue el mismo camino.

Todos los totales se calculan con SQL sobre la base.

## Requisitos

- Java 25 o más nuevo.
- Un bot de Telegram creado con [@BotFather](https://t.me/BotFather).
- Una API key de Anthropic.
- Para los audios: un servidor de Whisper con API compatible con la de OpenAI (ver
  [La VM: Whisper y PostgreSQL](#la-vm-whisper-y-postgresql)). Sin él, el bot funciona igual con texto.

No hace falta Docker en Windows: por defecto la base es H2 en un archivo dentro del proyecto.

## Configuración

Los secretos van en un archivo `.env` en la raíz del proyecto. Nunca se sube al repo: está en el `.gitignore`.
Copiá `.env.example` y completalo:

```properties
TELEGRAM_BOT_TOKEN=123456:ABC...        # el token que te da @BotFather
TELEGRAM_BOT_USERNAME=mi_gastito_bot
TELEGRAM_ALLOWED_USER_ID=123456789      # tu id numérico de Telegram (te lo dice @userinfobot)
ANTHROPIC_API_KEY=sk-ant-...

# Audios (servidor de Whisper en la VM)
WHISPER_URL=http://localhost:9000
WHISPER_API_KEY=...                      # docker exec whisper whisper_manage --getkey
```

El bot ignora en silencio los mensajes de cualquier usuario que no sea `TELEGRAM_ALLOWED_USER_ID`.

Otros ajustes, en `src/main/resources/application.yml`:

- `gastito.ia.modelo`: el modelo de Claude (por defecto `claude-haiku-5-5`).
- `gastito.ia.precios`: precio por millón de tokens de cada modelo, para calcular el costo en `/costos`.
- `gastito.audio.timeout`: cuánto esperar la transcripción (por defecto `60s`).
- `gastito.audio.duracion-maxima`: audios más largos no se procesan (por defecto `60s`).
- `gastito.audio.idioma` y `gastito.audio.modelo`: `es` y `whisper-1`.
- `gastito.zona-horaria`: por defecto `America/Argentina/Buenos_Aires`.

Las instrucciones que se le mandan a la IA y a Whisper están en `src/main/resources/prompts/` y se pueden
ajustar sin tocar código.

## Arrancar el bot (lo más fácil)

Doble clic en **`iniciar.bat`**, en la raíz del proyecto. Arranca con el perfil `postgres` y antes revisa:

1. Que esté Java (Maven no hace falta: usa el wrapper `mvnw`).
2. Que el `.env` tenga todas las variables (no muestra los valores).
3. Que no haya otro Gastito prendido. Si hay uno, pregunta si lo apaga: dos a la vez se pisan en Telegram.
4. Que PostgreSQL responda. Si la VM todavía está arrancando, espera hasta 3 minutos.
5. Que responda el servidor de Whisper. Si no, avisa y arranca igual: con texto funciona, solo fallan los audios.
6. Que el jar esté al día. Compila solo la primera vez y cuando cambió el código (sin correr los tests).

Cuando en la ventana aparece `Bot de Telegram conectado y escuchando mensajes`, ya se puede usar. **Para
apagarlo, cerrá la ventana.** Si algo falla, la ventana queda abierta con el motivo.

Para arrancar con H2 en lugar de PostgreSQL, desde una terminal en la raíz del proyecto:

```bash
iniciar.bat dev
```

Las secciones de abajo explican cómo arrancarlo a mano con el Maven Wrapper (`./mvnw`; en Windows sin Git Bash, `mvnw.cmd`) o con el jar. La primera vez descarga Maven solo.

## Correr en perfil `dev` (H2, sin Docker)

```bash
./mvnw spring-boot:run
```

`dev` es el perfil por defecto. La base queda en `./data/gastito.mv.db`; Flyway crea las tablas y carga las
categorías, medios de pago y cuentas la primera vez. Para empezar de cero, frená la aplicación y borrá la
carpeta `data/`.

Cuando en el log aparece `Bot de Telegram conectado y escuchando mensajes`, abrí el chat con tu bot y mandá
`/start`.

Para generar un jar y correrlo sin Maven:

```bash
./mvnw package
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

### Audios

Mantené apretado el micrófono y decí el gasto como lo escribirías: *"gasto 3700 en pan de pancho y papas,
efectivo"*. Mientras transcribe, el bot muestra "escribiendo…". Llega la misma tarjeta que con un texto, con lo
que escuchó arriba:

> 🎙 Escuché: "Gasto 3700 en pan de pancho y papas, efectivo"

Así, si Whisper entendió mal, lo ves antes de guardar. También acepta archivos de audio (mp3, m4a, ogg…).

- Si estás editando un campo (monto, fecha o descripción) y mandás un audio, la tarjeta te muestra lo que
  entendió, por ejemplo `🎙 Escuché: "3700" → Monto: $3.700`, con **✅ Aplicar** y **↩ Cancelar edición**. Si lo
  que dijiste no sirve para ese campo, el audio se toma como un gasto nuevo.
- Audios de más de 60 segundos no se procesan: mandá uno más corto.
- Si el servidor de Whisper no responde, el bot te avisa y te pide el gasto escrito.
- Whisper corre local y no cuesta nada: no se registra en `uso_ia`. La llamada a Haiku que interpreta el texto
  transcripto, si hace falta, sí se registra, con propósito `AUDIO`.

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

2. Corré la aplicación con el perfil `postgres`: doble clic en `iniciar.bat`, o a mano:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
   ```

   o con el jar:

   ```bash
   java -jar target/gastito-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres
   ```

Flyway crea las mismas tablas en PostgreSQL. Los datos que cargaste en H2 no se copian solos.

## La VM: Whisper y PostgreSQL

El servidor de Whisper y PostgreSQL corren en Docker dentro de una VM de Ubuntu en VirtualBox, con la red en
NAT. Desde Windows se llega a los dos por `localhost`, reenviando los puertos.

**Whisper** (imagen `hwdsl2/whisper-server`, API compatible con la de OpenAI). En la VM:

```bash
docker run -d --name whisper --restart=always -v whisper-data:/var/lib/whisper -p 9000:9000 \
  -e WHISPER_MODEL=small -e WHISPER_LANGUAGE=es hwdsl2/whisper-server

# La clave de la API, que va en WHISPER_API_KEY del .env:
docker exec whisper whisper_manage --getkey
```

Si la transcripción es lenta, se puede cambiar `WHISPER_MODEL=small` por `base`, borrando y recreando el
contenedor (`docker rm -f whisper` y el `docker run` de nuevo).

**PostgreSQL**. En la VM, con la misma contraseña que `DB_PASSWORD` del `.env`:

```bash
docker run -d --name gastito-db -e POSTGRES_DB=gastito -e POSTGRES_USER=gastito -e POSTGRES_PASSWORD=<clave> \
  -p 5432:5432 -v gastito-data:/var/lib/postgresql/data --restart unless-stopped postgres:16
```

**Reenvío de puertos en VirtualBox:** Configuración > Red > Adaptador 1 (NAT) > Avanzadas > Reenvío de puertos.
Una regla TCP de `127.0.0.1:9000` (anfitrión) a `9000` (invitado) para Whisper, y otra igual con el puerto `5432`
para PostgreSQL.

Comprobación desde Windows (PowerShell):

```powershell
Test-NetConnection 127.0.0.1 -Port 9000
Test-NetConnection 127.0.0.1 -Port 5432
```

## Tests

```bash
./mvnw test
```

Usan H2 en memoria y no se conectan a Telegram, a Anthropic ni a Whisper: el servidor de Whisper se simula con
WireMock, así que no hace falta Docker.

Hay además un test que llama a la API real de Anthropic con la key del `.env`. Sirve para revisar el prompt
después de cambiarlo y cuesta fracciones de centavo. No corre con `./mvnw test`; se corre a mano:

```bash
./mvnw test -Dtest=InterpretacionRealIT -Dsurefire.failIfNoSpecifiedTests=false
```

## Estructura

El código está organizado por funcionalidad, no por capa técnica:

```
ar.gastito
├── bot              Telegram: filtro de usuario, conversación, tarjetas de gasto, formato de mensajes
├── interpretacion   Parser simple, historial, validación de la respuesta de la IA, fechas relativas
├── ia               Cliente de Claude, prompts, registro de tokens y costo (uso_ia)
├── audio            Transcripción de audios con el servidor de Whisper
├── catalogo         Categorías, medios de pago, cuentas, tarjeta de crédito y alias de comercios
├── movimiento       El movimiento (gasto, ingreso, etc.), su ciclo pendiente → confirmado y los totales
└── config           Propiedades y reloj en hora de Argentina
```

El resto del repositorio:

```
├── src/main/resources
│   ├── application.yml      Configuración y perfiles (dev, postgres)
│   ├── db/migration/        Migraciones de Flyway (esquema y datos iniciales)
│   └── prompts/             Instrucciones para la IA y para Whisper, editables sin tocar código
├── src/test                 Tests con JUnit 5 (H2 en memoria)
├── scripts/iniciar.ps1      Arranque con chequeos previos (lo llama iniciar.bat)
├── docs/pedidos/            Los pedidos de cada etapa con los que se fue construyendo el bot
├── docker-compose.yml       PostgreSQL para el perfil postgres
└── mvnw, mvnw.cmd           Maven Wrapper
```
