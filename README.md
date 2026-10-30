# Gastito

Bot de Telegram personal para registrar gastos. Le escribís algo como `kiosco 1500` o
`3700 en pan de pancho y papas, efectivo` (o se lo decís en un audio, o le mandás la foto del ticket), te
muestra lo que entendió y lo guardás con un botón.

Cómo se interpreta un mensaje, de lo más barato a lo más caro:

1. **Parser simple, sin IA:** `kiosco 1500`, `1500 super débito`.
2. **Historial:** si ya guardaste gastos con una descripción igual o muy parecida y siempre en la misma
   categoría, se usa esa sin llamar a la IA.
3. **Claude Haiku:** devuelve hasta 3 categorías posibles y qué tan seguro está. Si no está seguro, el bot te
   pregunta con botones.

Los audios se transcriben con un servidor de Whisper local y el texto sigue el mismo camino. Las fotos de
tickets las lee Haiku (visión): comercio, fecha, productos y total; Java controla que los productos sumen el
total antes de mostrar la tarjeta.

El sueldo se calcula con una calculadora (conceptos y escalas configurables, tus horas del mes), se carga cuando
se cobra y, una semana después, el recibo (el PDF, o una foto) se guarda para **controlarlo**: Gastito lo compara con
el cálculo y con lo cobrado y avisa si hay algo raro. El recibo no crea ningún ingreso.

Para mirar y analizar hay un **dashboard web** servido por la misma aplicación: gasto del mes, comparación con el
mes anterior, gasto por día, por categoría y por billetera, movimientos con buscador, e ingresos (sueldo,
aguinaldo y extras) con lo que queda disponible.

Además te manda **resúmenes**: el domingo a las 21, cómo fue la semana, y el día 1 a las 9, cómo fue el mes. Y le
podés **preguntar** en lenguaje natural ("¿cuánto gasté en kiosco este mes?"), por escrito o por audio.

Todos los totales se calculan con SQL o Java sobre la base; ni la IA ni el navegador hacen cuentas.

## Requisitos

- Java 25 o más nuevo.
- Un bot de Telegram creado con [@BotFather](https://t.me/BotFather).
- Una API key de Anthropic.
- Para los audios: un servidor de Whisper con API compatible con la de OpenAI (ver
  [La VM: Whisper y PostgreSQL](#la-vm-whisper-y-postgresql)). Sin él, el bot funciona igual con texto.
- Para las fotos de tickets no hace falta nada más: usa el mismo modelo, Claude Haiku, que acepta imágenes.

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

# Opcional: abrir el dashboard en el celular (ver "Dashboard web")
#SERVER_ADDRESS=0.0.0.0
#GASTITO_DASHBOARD_TOKEN=...
```

El bot ignora en silencio los mensajes de cualquier usuario que no sea `TELEGRAM_ALLOWED_USER_ID`.

Otros ajustes, en `src/main/resources/application.yml`:

- `gastito.ia.modelo`: el modelo de Claude (por defecto `claude-haiku-5-5`).
- `gastito.ia.precios`: precio por millón de tokens de cada modelo. El costo de cada llamada se calcula al guardarla
  y queda fijo: si los precios cambian, el historial no se recalcula.
- `gastito.ia.tope-mensual-usd`: tope de gasto en IA por mes, en dólares (por defecto `2`). No bloquea nada: el bot
  avisa una vez al llegar al 80 % y otra al 100 %. Con `0` se apagan los avisos.
- `gastito.recibos.max-tokens`: tope de la respuesta de la IA al leer un recibo (por defecto `3000`).
- `gastito.sueldo.tolerancia-cobro-porcentaje`: desde qué diferencia (%) entre lo calculado y lo cobrado se marca
  con ⚠️ (por defecto `1`). `gastito.sueldo.tolerancia-concepto` ($ por concepto, `1`) y
  `gastito.sueldo.tolerancia-porcentaje` (puntos de un descuento, `0.05`) son las del control del recibo.
- `gastito.audio.timeout`: cuánto esperar la transcripción (por defecto `60s`).
- `gastito.audio.duracion-maxima`: audios más largos no se procesan (por defecto `60s`).
- `gastito.audio.idioma` y `gastito.audio.modelo`: `es` y `whisper-1`.
- `gastito.ticket.lado-maximo`: la foto se achica a este lado más largo antes de mandarla (por defecto `1568`).
- `gastito.ticket.tamanio-maximo`: imágenes más pesadas no se procesan (por defecto `10MB`).
- `gastito.ticket.max-tokens`: tope de la respuesta de la IA al leer un ticket (por defecto `4096`).
- `gastito.ticket.carpeta-datos`: las fotos quedan en `<carpeta>/tickets/AAAA/MM/<id>.jpg` (por defecto `data`).
- `gastito.resumenes.hora-semanal` y `hora-mensual`: cuándo se mandan los resúmenes (`21:00` del domingo y `09:00`
  del día 1).
- `gastito.resumenes.umbral-hormiga` y `categorias-hormiga`: qué cuenta como gasto hormiga (por defecto, menos de
  $3.000 en Kiosco o Comida afuera / delivery).
- `gastito.resumenes.modelo-semanal`, `modelo-mensual` y `max-tokens`: el modelo que redacta cada resumen.
- `gastito.resumenes.minimo-semanal` y `minimo-mensual` (`gastos` y `dias`): con menos datos, el resumen no lleva
  consejo ni escenarios de ahorro (por defecto 5 gastos en 3 días, y 15 gastos en 7 días).
- `gastito.repetidos.ventana`: cuánto antes o después de un gasto nuevo se busca otro igual (por defecto `3h`).
- `gastito.consultas.modelo`, `max-tokens` y `max-llamadas`: el modelo que responde preguntas y cuántas
  herramientas puede usar por pregunta (por defecto 3).
- `gastito.zona-horaria`: por defecto `America/Argentina/Buenos_Aires`.

Las instrucciones que se le mandan a la IA y a Whisper están en `src/main/resources/prompts/` y se pueden
ajustar sin tocar código.

## Arrancar el bot (lo más fácil)

Doble clic en **`iniciar.bat`**, en la raíz del proyecto. Arranca con el perfil `postgres`, la base real (ver
[Dos bases: prueba y real](#dos-bases-prueba-y-real)), y antes revisa:

1. Que esté Java (Maven no hace falta: usa el wrapper `mvnw`).
2. Que el `.env` tenga todas las variables (no muestra los valores).
3. Que no haya otro Gastito prendido. Si hay uno, pregunta si lo apaga: dos a la vez se pisan en Telegram.
4. Que PostgreSQL responda. Si la VM todavía está arrancando, espera hasta 3 minutos.
5. Que responda el servidor de Whisper. Si no, avisa y arranca igual: con texto funciona, solo fallan los audios.
6. Que el jar esté al día. Compila solo la primera vez y cuando cambió el código (sin correr los tests).

Cuando en la ventana aparece `Bot de Telegram conectado y escuchando mensajes`, ya se puede usar. Al arrancar,
la consola (`Gastito corriendo con la base ...`) y un mensaje de Telegram dicen con qué base está corriendo
(🟢 la real, 🧪 la de prueba). **Para apagarlo, cerrá la ventana.** Si algo falla, la ventana queda abierta con
el motivo.

Para arrancar con otra base, desde una terminal en la raíz del proyecto:

```bash
iniciar.bat prueba     # la base de prueba (gastito_prueba), para probar cambios
iniciar.bat dev        # H2 en un archivo, sin PostgreSQL
```

`scripts\iniciar.ps1 -Automatico` es la variante para el Programador de tareas (ver
[Arranque automático](#arranque-automático)): no pregunta nada, espera hasta 10 minutos a PostgreSQL y, si ya
hay un Gastito prendido, no hace nada.

## Dos bases: prueba y real

Hay dos bases en el mismo PostgreSQL de la VM, con las mismas migraciones y el mismo usuario del `.env`:

| Perfil     | Base             | Para qué                                                                         |
|------------|------------------|----------------------------------------------------------------------------------|
| `postgres` | `gastito`        | La real, con los datos de verdad. Es la que usa `iniciar.bat`.                    |
| `prueba`   | `gastito_prueba` | Solo para probar cambios, con datos inventados (`iniciar.bat prueba`).            |

El perfil `prueba` toma la URL de `DB_PRUEBA_URL` si está en el `.env`; si no, usa
`jdbc:postgresql://localhost:5432/gastito_prueba`. Las fotos de sus tickets van a `data/prueba/tickets/`, aparte
de las reales (`data/tickets/`), porque los ids de las dos bases se repiten. Como las dos usan el mismo bot de
Telegram, no se pueden tener prendidas a la vez (`iniciar.bat` avisa).

La base de prueba se crea una sola vez, en la VM:

```bash
docker exec gastito-db createdb -U gastito -O gastito gastito_prueba
```

### Datos de prueba

**`generar-datos-prueba.bat`** (doble clic) borra lo que haya en `gastito_prueba` y la llena con unos 3 meses
de datos creíbles, hasta hoy. Se puede correr las veces que haga falta, y la semilla es fija, así que con la
misma fecha sale lo mismo:

- De 2 a 5 gastos por día, en todas las categorías y medios de pago, con montos típicos de cada una. Algunos
  vienen por audio.
- Gastos hormiga en el kiosco casi todos los días.
- Servicios, suscripciones, el seguro (FEDERA8) y el impuesto de sellos, todos los meses el mismo día.
- Algunas compras en el Día con el detalle del ticket y el 20% de ClubDía, basadas en el ticket real de ejemplo.
- El mes del medio es caro (más salidas, ropa y compras grandes, alrededor de un 25% más), para que las
  comparaciones y los consejos tengan algo para mostrar.
- Seis meses de sueldo (cobrados el día 5 del mes siguiente): dos escalas (una paritaria a mitad de camino), el cálculo, lo cobrado y el recibo de cada mes
  (uno con la ley 19.032 al 2,5 %, una cosa rara; el último mes cobró $4.400 menos de lo calculado y todavía no
  tiene recibo), el aguinaldo y algunos extras (con
  descripción). Cada recibo trae sus conceptos y un PDF inventado, para probar **Ver recibo**. Borra y vuelve a
  cargar también las escalas.
- Unas 150 llamadas a la IA por mes en `uso_ia` (marcadas con el modelo `claude-haiku-5-5 (prueba)`), para ver la
  pantalla de costos. Con el tope en USD 2 no llegan al aviso: para verlo, bajá `gastito.ia.tope-mensual-usd`
  (por ejemplo a `0.05`).

Borra movimientos e ingresos (y con ellos lo aprendido), recibos, tickets, resúmenes y sus envíos, y los archivos
de `data/prueba/tickets/` y `data/prueba/recibos/`. Deja el catálogo y las llamadas de `uso_ia` que no son de
prueba, que es plata de verdad gastada en la API mientras se probaba. El generador está en `ar.gastito.prueba`: solo existe con el perfil `prueba` y, antes de borrar nada,
comprueba que la base se llame `gastito_prueba` y que no esté activo el perfil `postgres`. Con la base real no
corre nunca.

### Lanzamiento

Desde el 28/10/2026 `iniciar.bat` arranca con la base real. El primer arranque con `postgres` aplica las
migraciones que le faltaban a `gastito` (estaba en la V6). El 25/10/2026 la base `gastito` se vació (tenía las
primeras pruebas); su backup quedó en `data/backups/gastito-2026-10-25.dump` y se puede restaurar aparte con
`scripts\restaurar.ps1 -Backup data\backups\gastito-2026-10-25.dump -BaseNueva gastito_backup`.

## Backup

**`backup.bat`** (doble clic, en la raíz; llama a `scripts\backup.ps1`) hace un `pg_dump` de la base real
(dentro del contenedor `gastito-db` de la VM, por SSH) y una copia de `data/` (fotos de tickets, recibos; sin
`data/backups/`). Cada backup es una carpeta `gastito-AAAA-MM-DD_HHmm` con `gastito.dump` y `data\`, y se
conservan los últimos 30.

- Carpeta: `BACKUP_DIR` en el `.env` (o `-Destino <carpeta>` para una vez). **Conviene que sea una carpeta
  sincronizada con la nube**, así el backup sobrevive aunque se rompa la PC: por ejemplo, con
  [Google Drive para escritorio](https://www.google.com/drive/download/) instalado,
  `BACKUP_DIR=G:\Mi unidad\Backups\gastito` (la letra de la unidad puede cambiar: fijate en el Explorador). Sin
  `BACKUP_DIR`, usa `%USERPROFILE%\Backups\gastito`, que queda solo en esta PC, y lo avisa.
- Los dumps tienen todos tus movimientos: la carpeta de la nube tiene que ser privada (no la compartas).
- Necesita el alias SSH `devbox` en `~/.ssh/config` (la VM, con el puerto 22 reenviado al 2222 de Windows) con
  clave, sin contraseña. Los scripts usan `ssh -4`: en Windows `localhost` resuelve primero a `::1` y el reenvío
  de VirtualBox escucha solo en IPv4.

**`scripts\restaurar.ps1`** restaura el backup más reciente en una base **nueva** (`gastito_restaurada`) del
mismo PostgreSQL y compara las filas de cada tabla con `gastito`, para comprobar que el backup sirve sin tocar la
real. Nunca restaura sobre `gastito` ni `gastito_prueba`.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup.ps1
powershell -ExecutionPolicy Bypass -File scripts\restaurar.ps1 -Borrar        # prueba y borra la base nueva
powershell -ExecutionPolicy Bypass -File scripts\restaurar.ps1 -Backup <carpeta> -BaseNueva otra -Reemplazar
```

Para recuperar la base real de verdad: restaurá en una base nueva, revisala, apagá Gastito y renombrala en la VM
(`docker exec gastito-db psql -U gastito -d postgres -c "ALTER DATABASE gastito RENAME TO gastito_vieja"` y
después `gastito_restaurada` a `gastito`). Los archivos se recuperan copiando la carpeta `data\` del backup.

### Backup diario con el Programador de tareas

En PowerShell (como tu usuario, que es el que tiene la clave SSH), desde la raíz del proyecto:

```powershell
$accion = New-ScheduledTaskAction -Execute 'powershell.exe' `
  -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PWD\scripts\backup.ps1`""
$cuando = New-ScheduledTaskTrigger -Daily -At 22:00
# StartWhenAvailable: si a esa hora la PC estaba apagada, lo hace apenas se prende.
$opciones = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
Register-ScheduledTask -TaskPath '\Gastito\' -TaskName 'Backup' -Action $accion -Trigger $cuando -Settings $opciones
```

Para ver si anduvo: Programador de tareas > Gastito > Backup ("Resultado de la última ejecución" 0x0) y la
carpeta de backups. Para probarlo ya: `Start-ScheduledTask -TaskPath '\Gastito\' -TaskName 'Backup'`.

## Arranque automático

Para que todo arranque solo al prender la PC (al iniciar sesión), en este orden:

1. **La VM de Ubuntu** (`devbox`), sin ventana:

   ```powershell
   $vbox = 'C:\Program Files\Oracle\VirtualBox\VBoxManage.exe'
   $accion = New-ScheduledTaskAction -Execute $vbox -Argument 'startvm devbox --type headless'
   Register-ScheduledTask -TaskPath '\Gastito\' -TaskName 'VM' -Action $accion `
     -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)
   ```

   Para verla o apagarla: VirtualBox > devbox > Mostrar, o `VBoxManage controlvm devbox acpipowerbutton`.

2. **Docker y PostgreSQL en la VM.** Docker arranca con el sistema (`sudo systemctl enable docker`) y los
   contenedores vuelven solos por su política de reinicio: `gastito-db` con `--restart unless-stopped` y
   `whisper` con `--restart=always` (ver [La VM](#la-vm-whisper-y-postgresql)). Para revisarlo:
   `docker inspect -f '{{.Name}} {{.HostConfig.RestartPolicy.Name}}' gastito-db whisper`. Si se creó con el
   `docker-compose.yml`, ya tiene `restart: unless-stopped`.

3. **La app**, un minuto después, con `iniciar.ps1 -Automatico`, que espera hasta 10 minutos a que PostgreSQL
   responda y compila si hace falta. Queda en una ventana: para apagarlo, cerrala.

   ```powershell
   $accion = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $PWD `
     -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PWD\scripts\iniciar.ps1`" -Automatico"
   $cuando = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
   $cuando.Delay = 'PT1M'
   $opciones = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero)   # sin límite de tiempo
   Register-ScheduledTask -TaskPath '\Gastito\' -TaskName 'App' -Action $accion -Trigger $cuando -Settings $opciones
   ```

Al arrancar, el bot avisa por Telegram con qué base está corriendo: ese mensaje es la señal de que todo levantó.
Si no llega, abrí `iniciar.bat` a mano para ver el motivo.

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

### Fotos de tickets

Sacale una foto al ticket y mandásela al bot (como foto, o como archivo de imagen). Mientras lo lee muestra
"escribiendo…", y llega la tarjeta de siempre con el detalle:

```
🧾 Ticket · Supermercado Día
📅 sáb 18/10 17:20
🛒 Productos
Leche 1 L ×2 .......... $2.900
Yerba 500 g ........... $3.900
Galletitas ............ $1.250
💰 Total $8.050
💳 Débito
🛒 Supermercado
```

Con **✅ Guardar**, **✏️ Editar** y **❌ Cancelar**, igual que un gasto escrito. Se editan los mismos campos; la
descripción arranca con el nombre del comercio.

- El monto es el **total impreso**. Gastito hace las cuentas en Java. Si el ticket imprime el subtotal, revisa
  tres cosas: que los productos sumen el subtotal, que los descuentos sumen lo que dice el ticket y que el
  subtotal menos los descuentos dé el total. Si no, que los productos menos los descuentos den el total. Cada
  cuenta que no cierra (por más de $10) aparece en la tarjeta, por ejemplo *⚠️ Los productos suman $38.133 y el
  subtotal dice $38.333*, junto con las líneas que no se pudieron leer si el ticket estaba arrugado.
- Los descuentos (por ejemplo el *20% ClubDía* de cada producto) quedan debajo de su producto, en el orden
  impreso. Las líneas de *Ahorro* son informativas y no se restan otra vez.
- Si una cuenta no cierra, la tarjeta nombra también los productos donde cantidad × precio unitario no da el
  importe del renglón (casi siempre, un número tapado por un pliegue).
- Los números se entienden con coma o con punto decimal, aunque se mezclen en el mismo ticket, y la fecha como
  `22-09-2026 17:44:28`, `22/09/2026` o `22/09/26`.
- En una franquicia, el comercio es el que figura en "a cuenta y orden de" (Día), no la razón social del
  franquiciado.
- De cada producto se guarda la descripción legible y la impresa (`GALLET CRACKER QSO`), y el código de barras si
  es un EAN-8 o EAN-13 válido. Del ticket se guarda la sucursal (`5601`), para cruzarlo más adelante con el
  resumen de la tarjeta.
- Si el ticket dice solo *TARJETA*, sin aclarar débito o crédito, queda sin medio de pago para que lo elijas
  (salvo que lo hayas escrito con la foto).
- Por ahora todo el ticket va a una sola categoría. La categoría por producto está anotada en
  [docs/mejoras-futuras.md](docs/mejoras-futuras.md).
- Si no se lee el total, no se guarda nada y te pide una foto más clara, con el ticket entero y sin reflejos.
- La fecha es la del ticket. Una fecha futura o de hace más de un año se toma como mal leída y queda la de hoy.
- Si escribís algo con la foto, se usa como pista: *"efectivo"*, *"con la de Galicia"*.
- Muestra hasta 8 productos y "…y N más". El detalle completo se ve en el dashboard.
- Antes de mandarla, la foto se endereza, se achica a 1568 px de lado y se pasa a JPEG: se lee igual y cuesta
  menos tokens. Más de 10 MB no se procesa; una imagen HEIC o WebP mandada como archivo, tampoco (mandala como
  foto).
- Un ticket por foto. Si es muy largo y sale borroso, sacalo en dos partes.
- La lectura queda en `uso_ia` con propósito `TICKET`.

**Privacidad.** Los tickets traen los últimos números de la tarjeta, el CUIT, la caja, el nombre del cajero o el
número de cliente (ClubDía). En la base se guarda solo el comercio, la sucursal, la fecha, los productos (tabla
`movimiento_item`), el subtotal, los descuentos, el total y el medio de pago: la respuesta de la IA no tiene campos
para lo demás. Además, en los textos libres (descripciones, comercio) Java borra los números de tarjeta y los
CUIT que se hayan colado. Los números no se borran por su largo: un código de barras identifica un producto, no a
una persona. La foto (achicada y sin los metadatos de la original) queda en
`data/tickets/AAAA/MM/<id>.jpg`, que está en el `.gitignore`; si cancelás el ticket, se borra. Para leerla, la
foto viaja a la API de Anthropic.

Lo que guardás le enseña al bot: la próxima vez que cargues algo parecido usa la misma categoría, y tus últimas
20 confirmaciones van como ejemplos en el prompt de la IA. Con los tickets, la categoría que le diste a un
comercio manda sobre la que propone la IA.

### Compras repetidas

Si un gasto nuevo (por texto, audio o ticket) se parece mucho a uno que ya estaba, en lugar de la tarjeta de
siempre llegan los dos lado a lado, **Ya registrado** y **Nuevo**. Se parecen si tienen:

- el mismo monto, con una tolerancia del 1% o $50, lo que sea mayor;
- a no más de 3 horas de diferencia (`gastito.repetidos.ventana`);
- y la misma categoría o el mismo comercio, si los dos los tienen (el comercio se compara sin la sucursal:
  `DIA TIENDA 5601` = `Dia Tienda`).

Se compara contra los gastos confirmados (las cuotas futuras no) y contra los cobros pendientes de los gastos
fijos del mes, sin mirar la hora. En una juntada se compara el total pagado, no la parte de uno. Lo que genera
Gastito (cobros de fijos, cuotas) no se compara al crearse.

- **Es la misma: uní los datos.** Queda el que ya estaba, con lo mejor de los dos: los productos y la foto del
  ticket, la descripción dicha por texto o audio, el monto y la hora del ticket, y el medio de pago más completo.
  Si era el cobro pendiente de un fijo, se confirma con lo que se cargó. El otro se borra, y lo que aportó (su
  origen, monto, hora y texto) queda en la tabla `movimiento_union`.
- **Compré de nuevo: guardá las dos.** Se guarda el nuevo. Si le falta algo, o es un ticket, vuelve la tarjeta de
  siempre para revisarlo.

La detección (`RepetidosService`, sobre una `Huella`: fecha, monto, categoría y comercio) está pensada para usarse
también con los avisos del banco y el resumen de la tarjeta de la etapa 7.

### Juntadas

```
pagué 48000 el asado, éramos 6, con mercado pago
la pizza con Martín, Sofi y Pablo, 30000
```

La IA detecta la juntada y extrae el total y cuántas personas eran (o los nombres de los demás). Divide Java: las
partes de los demás van redondeadas a peso, para abajo, y la diferencia del redondeo queda en la tuya.

- **En tus gastos cuenta solo tu parte** ($8.000), con la marca `juntada`. El resto ($40.000) queda **a cobrar**,
  anotado en la juntada junto con la cuenta o tarjeta desde donde salió el total. "Por billetera" muestra tu parte;
  para cruzarla con un aviso o con el resumen de la tarjeta se usa el total (`juntada.total_pagado`).
- La tarjeta dice **Pagaste / Tu gasto real / A cobrar** y la lista de personas. Sin nombres, quedan como
  "Persona 2…6", y con **👥 Nombres** se renombran.
- **💲 La parte de alguien** ("Martín no comió carne, puso 5000"): las demás partes que no se tocaron se acomodan
  para que el total siga cerrando. Cambiar el monto de la tarjeta cambia el total y reparte de nuevo.
- **Cobrar:** "Pablo me pasó 8000" o "me devolvieron 8000 del asado". Se guarda como `DEVOLUCION` (no es ingreso)
  y Pablo queda pagado. Se busca por el nombre, por la juntada y por el monto; si queda más de una posible,
  pregunta con botones. Si paga **menos** de lo que debe, pregunta: **Queda debiendo $X** (sigue pendiente por la
  diferencia, y la próxima devolución se compara con eso) o **Está saldado** (queda pagado y la diferencia no se
  reclama). Si paga **de más**, se guarda con el aviso de la diferencia. Si no hay juntadas con plata a cobrar,
  por ahora avisa y no guarda nada.
- **💬 Armar mensaje** da un texto listo para copiar y mandar al grupo, con quién debe cuánto. El bot no les
  escribe a otras personas.
- **🚫 Dar por perdido:** esa parte pasa a ser gasto tuyo, con la categoría, la billetera y la fecha de la juntada.
- `/juntadas` muestra las que tienen plata a cobrar, cada una con sus botones.

Los datos están en `juntada` (el gasto, el total, las personas y desde dónde se pagó) y `juntada_participante`
(nombre, monto, lo que ya pagó, si se cambió a mano, estado pendiente/pagado/perdido, fecha de pago, la última
devolución y el gasto de
una parte perdida).

### Gastos fijos

Lo que se cobra todos los meses (el seguro, el streaming, la luz, el gas). El alta es con un comando, sin IA:

```
/fijo seguro 32900 tarjeta día 4
/fijo luz 28.000 débito galicia el 10 bimestral
/fijo netflix 12 mil tarjeta dia 7
```

o en lenguaje natural ("agregá un fijo: el seguro de la moto, 32900 con tarjeta el 4"). Llega una tarjeta con el
nombre, el monto, la categoría (si no se reconoce, se elige con botones), el medio de pago y el primer cobro, para
**Guardar** o **Cancelar**. Si el día de este mes ya pasó, el primer cobro es el mes que viene.

- **El día del cobro** el bot crea el gasto pendiente y pregunta: "Se cobró el seguro de la moto: $32.900.
  ¿Confirmás?", con **Confirmar**, **Cambió el monto** (y lo escribís) y **Este mes no** (ese mes no se vuelve a
  pedir). Si el mes no tiene ese día (un 31), se cobra el último.
- **Bimestral:** cada dos meses desde el primer cobro.
- **Montos variables** (la luz, el gas y el agua lo son solos; cualquier otro, con "variable"): se propone el
  promedio de los últimos 3 cobros confirmados.
- **Aviso de aumento:** si el monto confirmado difiere más de un 5% del cobro anterior, "📈 El seguro subió de
  $32.900 a $36.100 (+9,7%)". En los de monto fijo, lo confirmado pasa a ser el monto esperado.
- **Con la PC apagada,** al arrancar se crean (y se preguntan) los cobros que quedaron sin registrar. Revisa al
  arrancar y cada 10 minutos.
- Los cobros son gastos normales, con el vínculo a su fijo (`movimiento.gasto_fijo_id` y `fijo_periodo`). Si cargás
  a mano un gasto que coincide con un cobro pendiente del mes, el bot ofrece unirlos (ver Compras repetidas) y el
  fijo queda confirmado con lo que cargaste.
- `/fijos` muestra los que tenés con el próximo cobro, un botón para darlos de baja y, otra vez, la pregunta de
  los cobros que no respondiste.
- Para la etapa 7 ya está `GastoFijoService.confirmarDesdeResumen`: el resumen de la tarjeta va a confirmar el
  cobro solo, con su monto.

### Ingresos

Tres comandos, uno por tipo. El aguinaldo va aparte del sueldo para que no infle la comparación entre meses;
cualquier otra cosa (un trabajo particular, una venta, un regalo) es un extra con descripción:

```
/sueldo 380.000 galicia
/aguinaldo 190 mil galicia
/ingreso 25000 arreglo de PC a mercado pago
/ingreso 5000 regalo en efectivo ayer
```

El monto acepta `380.000`, `380 mil` o `25k`. La cuenta se reconoce por su nombre o sus palabras clave; si no la
decís, se usa la del último ingreso de ese tipo. Como los gastos, llega una tarjeta con **✅ Guardar** y
**❌ Cancelar**. También se cargan y se borran desde el dashboard.

### Calculadora de sueldo

Tu sueldo en la realidad: a fin de mes **calculás** cuánto vas a cobrar, **cobrás** (lo cargás con `/sueldo`) y una
semana después te dan el **recibo**, que sirve para controlar, no para registrar. Gastito reproduce el Excel de forma
genérica: los conceptos y los montos cambian con las paritarias y no están en el código.

- **Conceptos** (tabla `concepto_sueldo`, se editan en el dashboard): `REMUNERATIVO_FIJO` (básico, presentismo,
  adicionales), `HORAS` (horas × valor de la hora normal × factor; el factor es una fracción: 3/2, 2/1, 8/60; si la escala
  trae el valor de esa hora, como la del 50 % y la del 100 % de la hoja del sindicato, se usa ese),
  `NO_REMUNERATIVO` (no lleva descuentos) y `DESCUENTO_PORCENTAJE` (sobre el bruto remunerativo).
- **Escalas con vigencia**: los montos fijos, el valor de la hora y los porcentajes de cada paritaria, con la fecha
  "desde" (siempre desde el día 1 de un mes). Cada mes usa la escala vigente y el historial se conserva. Cuando
  cambia la paritaria: dashboard → Ingresos → Calculadora → **Escalas y conceptos** → *Nueva escala (copia la
  última)*. La migración deja los conceptos pero **no los montos**: la primera escala la cargás vos.
- **Escalas desde la hoja del sindicato** (`ar.gastito.sueldo.escala`): mandale al bot la **foto** de la hoja
  ("Recomposición salarial", con la palabra *escala*; Haiku la lee una sola vez, propósito `ESCALA`) o el **Excel**
  (por el bot o en *Escalas y conceptos* → *Cargar desde la hoja del sindicato*, sin IA; una columna por mes con el
  mes arriba, o la plantilla de dos columnas). Cada columna es el mes trabajado. Las filas se reconocen por sus
  **nombres de fila** (tabla `alias_escala`, editables en el dashboard; sin mayúsculas, acentos, espacios ni puntos);
  las que no tienen alias (Art. 13 y 28, la asignación sin "6%") se ignoran, y lo que la hoja no trae (adicional CCT,
  descuentos) se mantiene de la escala anterior; sin escala anterior, mis descuentos por defecto 11 + 3 + 3 + 2,5 %
  (19,5 %, `gastito.sueldo.descuentos-por-defecto`). Antes de guardar muestra cada mes con su cambio, lo que se mantiene y
  el control con **Valores netos** = remunerativos × (1 − 20 %, el descuento fijo de la hoja y no el mío:
  `porcentaje-control-hoja`) + no remunerativos (aviso si difiere más de
  $5); si un mes ya tiene escala, qué cambia y que se reemplaza. *Descargar plantilla* baja el Excel con la escala
  vigente.
- **Cálculo** (Java, `BigDecimal`, centavos en cada concepto): bruto = fijos + horas; cada descuento = bruto ×
  porcentaje; neto de haberes = bruto − descuentos; **neto total = neto de haberes + no remunerativos**. Un ajuste
  puntual de un concepto pisa su importe ese mes. El cálculo guardado de cada mes (`calculo_sueldo`) no cambia si
  después se edita la escala.

En el bot:

```
este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas      (o: /calcular 10 6 30)
🧮 Sueldo de octubre 2026
⏱ 10 h horas al 50% · 6 h horas al 100% · 30 h horas nocturnas
Bruto ............... $843.000
Descuentos .......... −$168.600
Neto de haberes ..... $674.400
No remunerativo ..... $440.000
💰 Neto total ..... $1.114.400
[✅ Guardar] [✏️ Editar] [❌ Cancelar]
```

`/calcular` toma las horas en el orden de los conceptos de horas; `/calculo` muestra el guardado del mes. Nada se
guarda hasta tocar **Guardar**. En el dashboard (**Ingresos → Calculadora**) las horas dan el resultado en vivo con el
detalle por concepto.

**Cobrado contra calculado:** al cargar el sueldo (`/sueldo`), si ese mes tiene cálculo, la tarjeta dice
"Calculaste $1.114.400 · Cobraste $1.110.000 (−$4.400, −0,4%)". Si la diferencia pasa el 1 %
(`gastito.sueldo.tolerancia-cobro-porcentaje`) lo marca con ⚠️. El ingreso se guarda igual, por lo cobrado.

**Período del sueldo:** el sueldo se cobra alrededor del día 5 del mes siguiente. Cada sueldo (y aguinaldo) guarda la
fecha de cobro, que cuenta para el disponible de ese mes, y además el **período** (el mes trabajado), con el que se
vinculan el cálculo y el recibo. Por defecto el período es el mes anterior si se cobra hasta el día 15
(`gastito.sueldo.dia-limite-periodo-anterior`) y, si no, el mismo mes. La tarjeta lo muestra ("Sueldo de octubre ·
cobrado el 05/11") y el botón **📆 Cambiar el mes del sueldo** lo alterna.

### Recibos de sueldo

Mandale el recibo al bot y lo guarda para **controlarlo** (no crea ningún ingreso):

- **PDF** (como archivo): si tiene texto, Gastito lo extrae con PDFBox y a Haiku le manda solo el texto (cuesta
  menos que una imagen). Si es un escaneo, usa visión, como con los tickets.
- **Foto** del recibo impreso: escribí "recibo" (o "sueldo", "aguinaldo") junto con la foto. Si no, el bot la toma por
  un ticket y, si Haiku nota que es un recibo, la lee de nuevo como recibo (una llamada más).

Haiku extrae el empleador, el período, la fecha de pago, el tipo (sueldo mensual, aguinaldo, vacaciones o
liquidación final) y cada concepto con su importe y su grupo (remunerativo, no remunerativo o descuento), más los
totales impresos. **La IA no calcula**: Java controla que remunerativos + no remunerativos − descuentos den el neto
impreso (tolerancia de $1). Sin neto no se guarda nada.

El recibo queda **vinculado al sueldo cobrado de su período** (el ingreso `SUELDO`, o `AGUINALDO`, de ese mes). Si
todavía no cargaste el cobro, se guarda igual y se vincula solo cuando lo cargues; si el sueldo de octubre se cobró
el 3 de noviembre y está cargado en noviembre, se vincula a mano (`POST /api/recibos/{id}/vincular`). Otro recibo
del mismo mes y tipo reemplaza al anterior.

**Control de cosas raras** (lo calcula Java; no hay IA en las comparaciones):

- el neto del recibo contra lo cobrado;
- cada concepto contra tu cálculo: horas extra pagadas distintas de las cargadas, básico o adicionales distintos de
  la escala, conceptos del cálculo que el recibo no trae;
- los porcentajes de descuento contra los de la escala (por ejemplo, la ley 19.032 al 2,5 % en lugar del 3 %);
- conceptos que estaban el mes anterior y ahora no, y conceptos nuevos (un concepto que tu cálculo explica, como
  las horas en cero, no cuenta);
- los totales del recibo que no cierran.

Para comparar, la IA dice a qué concepto de tu sueldo corresponde cada renglón del recibo (o "otro"); lo que ya
coincide por nombre o ya se mapeó antes no se pregunta, y lo que responde se guarda en `mapeo_concepto_recibo` para
los recibos siguientes (una llamada `RECIBO` más, solo la primera vez que aparece un renglón nuevo).

```
💼 Recibo · Junio 2026 · Empresa de Prueba
Bruto ............... $1.212.800
Descuentos ........... −$150.696
  Jubilación           −$133.408
  …
💰 Neto ............. $1.062.104
🧮 Calculaste $1.058.240 · Cobraste $1.062.104 (+$3.864, +0,4%)
⚠️ Para revisar:
• Ley 19.032: te descontaron 2,5% y tu escala dice 3%.
[🗑 Descartar recibo]
```

Sin nada raro dice "✅ El recibo coincide con tu cálculo". **🗑 Descartar recibo** borra también el archivo.

Privacidad: no se guarda en la base el CUIL, el CBU, el legajo ni el domicilio (la respuesta de la IA no tiene dónde
ponerlos, y se borran del texto del PDF antes de mandarlo). El archivo queda en `data/recibos/AAAA/MM/` (en la
base de prueba, `data/prueba/recibos/`) y no sale de la PC.

En el dashboard (**Ingresos**) hay una fila por mes con **Calculado · Cobrado · Recibo**, las diferencias y un
**Detalle** desplegable (el cálculo concepto por concepto y el recibo con lo que encontró el control, más **Ver
recibo**), y el gráfico de los últimos 12 meses (bruto, descuentos y neto de los sueldos mensuales) con la variación
del neto contra el mes anterior y contra el mismo mes del año anterior. Y se le puede preguntar al bot: "¿cuánto me
descontaron de ganancias este año?", "¿cuánto aumentó mi sueldo desde enero?", "¿cuánto cobré de más o de menos
contra lo que calculé?".

### Costos de la IA

`/costos` muestra el total del mes en dólares, la proyección a fin de mes (lo gastado ÷ días transcurridos × días
del mes), cuánto del tope mensual lleva y el desglose por propósito. Cuando el mes llega al 80 % del tope
(`gastito.ia.tope-mensual-usd`) el bot avisa una sola vez ("La IA lleva USD 1,62 este mes (81% del tope de USD 2)"),
y otra al 100 %. No bloquea nada.

En el dashboard, la sección **Costos IA** tiene el total del mes y del anterior, la proyección, el tope, el desglose
por propósito (llamadas, tokens de entrada y salida, costo y **costo promedio por llamada**), el costo de cada día,
el historial de 6 meses y las 10 llamadas más caras del mes, para ver qué conviene optimizar.

### Resúmenes

El **domingo a las 21** llega el resumen de la semana (lunes a domingo) y el **día 1 a las 9**, el del mes que
terminó. Los arma Java: total, comparación con el período anterior hasta el mismo punto, categorías, el gasto más
grande, gastos hormiga, días sin gastos, ingresos y tres escenarios de ahorro (hormiga en 3 por semana, la
categoría que más subió de vuelta a su nivel anterior y la categoría más grande con un 10% menos). Haiku solo
redacta "lo que noté" y un consejo; cada número que escribe se compara con los que calculó Java y, si alguno no
aparece, se descarta el texto y quedan solo los números.

- **Lo que noté** son de 0 a 3 observaciones que aportan algo nuevo: un patrón, una relación entre datos, el cambio
  de una categoría. Si una repite un número que ya está arriba (total, cantidad, variación, días sin gastos,
  hormiga), Java la saca.
- **Con pocos datos no hay consejo:** con menos de 5 gastos, o con gastos en menos de 3 días distintos (15 gastos
  y 7 días en el mensual), no se calculan escenarios y en lugar del consejo va "Todavía hay pocos datos para darte
  un consejo. Seguí cargando tus gastos."
- **Proyecciones:** en el semanal, un ahorro se lleva a "por mes" solo si la categoría tiene gastos de al menos 2
  semanas; si no, se dice por semana.
- Lo que todavía no se calcula (por ejemplo, lo que te devuelven de una juntada) no lo menciona la IA: va como nota
  al pie, y solo si en el período hay algún gasto así.

Si la PC estaba apagada, al prenderla llega el resumen pendiente más reciente de cada tipo, con un botón **Ver
anteriores** si quedaron otros sin ver. La primera vez que corre no manda nada de antes.

`/resumen` arma la semana en curso hasta hoy y `/resumen mes`, el mes; si el período no terminó, dice que es
parcial ("semana parcial, hasta el jueves 23"). El último resumen aparece también en el dashboard, en "Lo que notó
la IA".

Para **forzar el resumen semanal** sin esperar al domingo, corré el último envío registrado una semana para
atrás (PostgreSQL). En menos de un minuto el bot ve que la última semana terminada no se mandó y la manda; no hace
falta reiniciar. No borres la fila: sin ninguna, lo toma como primer arranque y no manda nada.

```sql
DELETE FROM resumen_envio WHERE tipo = 'SEMANAL'
  AND desde = (SELECT MAX(desde) - 7 FROM resumen_envio WHERE tipo = 'SEMANAL');
UPDATE resumen_envio SET desde = desde - 7, hasta = hasta - 7 WHERE tipo = 'SEMANAL'
  AND desde = (SELECT MAX(desde) FROM resumen_envio WHERE tipo = 'SEMANAL');
```

Con `tipo = 'MENSUAL'` y `INTERVAL '1 month'` en lugar de `7` se fuerza el mensual.

### Preguntas

Cualquier mensaje que sea una pregunta, escrito o en audio, se responde con datos de la base:

```
¿cuánto gasté en kiosco este mes?
compará octubre con septiembre
¿qué compré en el Día la semana pasada?
¿cuántas Seven Up compré desde septiembre?
¿cuánto me queda disponible?
```

Haiku elige entre seis herramientas (total gastado, gasto por categoría, comparar períodos, listar movimientos,
buscar un producto de los tickets, ingresos y disponible), con fechas de una tabla de rangos que calcula Java o
con un rango explícito ("del 3 al 10 de octubre") que Java valida. Los números de la respuesta pasan por el mismo
control que los resúmenes: si la IA escribe uno que no vino de una herramienta, se muestran los resultados tal
cual los calculó Java. Si un mensaje tiene un monto y también parece pregunta (`¿kiosco 1500?`), el bot pregunta
con dos botones: **Es un gasto** o **Es una pregunta**.

### Comandos

Abajo del chat queda un menú fijo con estos comandos, que también están en el botón Menú de Telegram:

| Botón          | Comando      | Qué hace                                         |
|----------------|--------------|--------------------------------------------------|
|                | `/start`     | Saludo, ejemplos y el menú                       |
| 📊 Hoy         | `/hoy`       | Gastos de hoy y el total                         |
| 📅 Mes         | `/mes`       | Total del mes y barras por categoría             |
| 💸 Costos IA   | `/costos`    | Lo gastado en IA este mes, la proyección y el desglose |
|                | `/resumen`   | La semana hasta hoy; `/resumen mes`, el mes      |
|                | `/juntadas`  | Quién te debe plata de las juntadas              |
|                | `/fijo`, `/fijos` | Agregar un gasto fijo; los que tenés y su próximo cobro |
| 🖥 Dashboard   | `/dashboard` | El link del dashboard (se abre desde la PC)      |
| ❓ Ayuda       | `/ayuda`     | Cómo cargar gastos e ingresos                    |
|                | `/sueldo`, `/aguinaldo`, `/ingreso` | Cargar un ingreso                 |
|                | `/calcular 10 6 30`, `/calculo` | Calcular el sueldo del mes con tus horas / ver el guardado |

Las categorías, sus descripciones (las que lee la IA), sus emojis y las palabras clave del parser simple están en
la tabla `categoria` y se pueden cambiar en la base sin tocar código.

## Dashboard web

Con la aplicación prendida, abrí en el navegador de la PC:

```
http://localhost:8090
```

(Es el puerto de `server.port`; si lo cambiás con `SERVER_PORT`, el link de `/dashboard` cambia solo.)

- **Resumen:** gastado en el mes con la comparación contra el mes anterior *hasta el mismo día* (el 13 de octubre
  se compara con el 1 al 13 de septiembre), ingresos, disponible (lo cobrado menos lo gastado, con una barra) y
  promedio diario. Gasto por día con la línea del promedio y el día más alto destacado, por categoría, por
  billetera y los últimos movimientos.
- **Movimientos:** todos los gastos del mes, con buscador (sin importar tildes ni mayúsculas), filtros por
  categoría, medio de pago y origen (texto, audio o ticket), y páginas. Los gastos que vienen de un ticket tienen
  el botón **🧾 Productos**, que despliega el detalle, la suma contra el total y el link **Ver foto**.
- **Ingresos:** sueldo, aguinaldo y extras del mes, el detalle con **Borrar**, el sueldo mes por mes (calculado,
  cobrado y recibo, con el control del recibo y **Ver recibo**), la calculadora con sus escalas y conceptos, los
  últimos 6 meses, el gráfico del sueldo de los últimos 12
  meses y el formulario **Cargar ingreso**.
- **Juntadas** y **Cuotas** (solo lectura; se cargan y se cobran desde el bot): las juntadas abiertas con quién debe cuánto y las cerradas, y las compras en cuotas con lo que falta pagar en los próximos 6 meses. En el Resumen, la tarjeta **Fijos y juntadas** dice cuántos fijos faltan cargar y cuánta plata hay por cobrar.
- **Costos IA:** lo gastado en la IA (en USD) en el mes: totales, proyección, tope, desglose por propósito, costo por
  día, historial de 6 meses y las 10 llamadas más caras.
- **Exportar CSV:** los gastos e ingresos del mes para Excel en castellano (separador `;`, coma decimal).

Las billeteras salen de los datos: cada gasto va a su tarjeta o su cuenta, o a **Efectivo** si se pagó en
efectivo. Una cuenta o tarjeta nueva aparece sola (su color está en la columna `color` de `cuenta` o `tarjeta`).
Lo que no tiene medio de pago ni cuenta queda en **Sin especificar**, en gris claro: son los gastos para
corregir desde el bot. 

En la web no hay emojis: los íconos son SVG de [Lucide](https://lucide.dev) (licencia ISC), con copia local en `static/img/icons.svg` (anda sin internet). Cada categoría tiene su ícono (columna `categoria.icono`, se cambia en la sección **Categorías**, dentro de **Más** en el celular) y un color propio; el emoji queda para el bot. Los medios de pago llevan ícono y las cuentas, un círculo con la inicial.

Se adapta al celular (ver más abajo) y tiene modo oscuro según la configuración del sistema. Chart.js viene dentro del jar
(WebJar), así que anda sin internet.

### Abrirlo en el celular con Tailscale

Es la forma recomendada: el dashboard sigue escuchando solo en `127.0.0.1` (no cambies `SERVER_ADDRESS`) y
Tailscale lo publica únicamente para tus dispositivos, sin abrirlo a internet ni a la red local.

1. Instalá [Tailscale](https://tailscale.com/download) en la PC y en el celular, e iniciá sesión con la misma cuenta.
2. En la PC, con la aplicación prendida: `tailscale serve --bg http://127.0.0.1:8090` (si cambiaste `SERVER_PORT`,
   usá ese puerto).
3. `tailscale serve status` muestra la dirección (algo como `https://mi-pc.tu-red.ts.net`). Abrila en el celular.
4. Para dejar de publicarlo: `tailscale serve reset`.

En pantallas de menos de 720 px el dashboard muestra la navegación abajo (lo que no entra va en **Más**), las
tablas como tarjetas (tocar una tarjeta con productos los despliega) y todo en una columna.

### Alternativa: misma red Wi-Fi (sin Tailscale)

Por defecto el servidor escucha solo en `127.0.0.1`: nadie más de la red ve tus gastos. Para abrirlo en el
celular hacen falta las dos cosas juntas en el `.env` (sin token, la aplicación **no arranca** escuchando en la
red):

```properties
SERVER_ADDRESS=0.0.0.0
GASTITO_DASHBOARD_TOKEN=una-clave-larga-y-al-azar
```

Después, desde el celular, abrí una vez `http://<ip-de-la-pc>:8090/?token=<el-token>`. El token queda guardado
en una cookie del navegador del celular (30 días) y se saca de la URL. Desde la PC se sigue entrando sin token.
Un pedido que no viene de la PC y no trae el token recibe 401. Si Windows pregunta por el firewall, permití el
acceso solo en redes privadas.

### La API

La misma API va a servir para la Mini App de Telegram. Todo devuelve JSON con la plata como número con 2
decimales, y acepta `?mes=2026-10` (por defecto, el mes actual):

| Endpoint                          | Qué devuelve                                                   |
|-----------------------------------|----------------------------------------------------------------|
| `GET /api/resumen`                | Gastado, cantidad, promedio, comparación, ingresos y disponible |
| `GET /api/gastos/por-dia`         | Un valor por día (con los días en cero), promedio y día máximo  |
| `GET /api/gastos/por-categoria`   | Monto, cantidad y emoji por categoría, de mayor a menor         |
| `GET /api/gastos/por-billetera`   | Por tarjeta, cuenta, efectivo y sin especificar, con color      |
| `GET /api/movimientos`            | Paginado; filtros `categoria`, `medioPago`, `origen`, `q`       |
| `GET /api/movimientos/{id}/items` | Productos y descuentos de un ticket, su suma y si coincide con el total |
| `GET /api/movimientos/{id}/foto`  | La foto del ticket (JPEG)                                       |
| `GET /api/movimientos.csv`        | Gastos e ingresos del mes en CSV                                |
| `GET /api/costos-ia`              | Costo en USD, llamadas y desglose por propósito                 |
| `GET /api/costos-ia/panel`        | La sección Costos IA: mes y anterior, proyección, tope, por propósito, por día, historial y las más caras |
| `GET /api/recibos/{id}`           | Un recibo: conceptos, totales, el sueldo cobrado vinculado, lo calculado y lo raro que encontró el control |
| `GET /api/recibos/{id}/archivo`   | El recibo tal como se guardó (PDF o JPEG)                       |
| `POST /api/recibos/{id}/vincular` | Vincula el recibo a un sueldo cobrado (`movimientoId`; sin él, vuelve a buscarlo por período) |
| `DELETE /api/recibos/{id}`        | Descarta el recibo y su archivo                                 |
| `GET /api/recibos/serie`          | Bruto, descuentos y neto de los últimos meses (`hasta`, `meses`, por defecto 12) y la variación del neto |
| `GET /api/sueldo/meses`           | Mes por mes: calculado, cobrado y recibo, con las diferencias (`hasta`, `meses`) |
| `GET /api/sueldo/calculo`         | El cálculo guardado de un mes (`mes`); `POST` lo guarda, `POST /api/sueldo/calcular` es la cuenta en vivo |
| `GET/POST/PUT /api/sueldo/conceptos` | Los conceptos del sueldo                                     |
| `GET/POST/PUT /api/sueldo/escalas` | Las escalas con vigencia (la hora normal y el monto o porcentaje de cada concepto) |
| `GET /api/resumenes/ultimo`       | El último resumen: período, lo que notó la IA y el consejo (204 si no hay) |
| `GET /api/catalogo`               | Categorías, medios de pago, cuentas y tipos de ingreso          |
| `GET /api/ingresos`               | Ingresos del mes y totales por tipo                             |
| `GET /api/ingresos/por-mes`       | Total de los últimos meses (`hasta`, `meses`, por defecto 6)    |
| `POST /api/ingresos`              | Cargar un ingreso (JSON: `clase`, `monto`, `descripcion`, `cuentaId`, `fecha`) |
| `DELETE /api/ingresos/{id}`       | Borrar un ingreso (queda cancelado)                             |

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

2. Corré la aplicación con el perfil `postgres`: `iniciar.bat postgres`, o a mano:

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

Usan H2 en memoria y no se conectan a Telegram, a Anthropic ni a Whisper: Whisper y la API de Anthropic se
simulan con WireMock (incluida una respuesta con el JSON mal formado), así que no hace falta Docker. Las
imágenes de prueba de los tickets se generan en el test.

El ticket real de Día (`src/test/resources/tickets/dia-2026-09-22-esperado.json`, la lectura esperada sin la
foto) es un caso de prueba más: pasa por la validación con los dos formatos de número, los tres controles y
los códigos de barras.

Hay además tests que llaman a la API real de Anthropic con la key del `.env`. Sirven para revisar los prompts
después de cambiarlos y cuestan fracciones de centavo. No corren con `./mvnw test`; se corren a mano:

```bash
./mvnw test -Dtest=InterpretacionRealIT -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Dtest=LecturaTicketRealIT -Dsurefire.failIfNoSpecifiedTests=false
```

`LecturaTicketRealIT` lee un ticket dibujado en el test y, si está, la foto real en
`data/tickets-prueba/dia-2026-09-22.jpg` (fuera del repo, porque `data/` está en el `.gitignore`), y la compara
con la lectura esperada renglón por renglón. Sin la foto, esa parte se saltea.

## Estructura

El código está organizado por funcionalidad, no por capa técnica:

```
ar.gastito
├── bot              Telegram: filtro de usuario, conversación, tarjetas de gasto, formato de mensajes
├── interpretacion   Parser simple, historial, validación de la respuesta de la IA, fechas relativas
├── ia               Cliente de Claude, prompts, registro de tokens y costo (uso_ia), costos y tope mensual
├── audio            Transcripción de audios con el servidor de Whisper
├── ticket           Fotos de tickets: achicar la imagen, leerla con Haiku, validar y guardar la foto
├── sueldo           Calculadora de sueldo: conceptos, escalas con vigencia, cálculo mensual y cobrado vs calculado
├── recibo           Recibos de sueldo como control: leer el PDF o la foto, validar, guardar y buscar lo raro
├── resumen          Resúmenes semanales y mensuales: cálculo, redacción con Haiku y registro de envíos
├── consulta         Preguntas en lenguaje natural: herramientas con SQL y conversación con tool use
├── juntada          Juntadas: el reparto (en Java), lo que me deben, devoluciones y partes perdidas
├── fijo             Gastos fijos: calendario de cobros, montos variables, aviso de aumento
├── catalogo         Categorías, medios de pago, cuentas, tarjeta de crédito y alias de comercios
├── movimiento       Gastos e ingresos, su ciclo pendiente → confirmado y los totales
├── dashboard        API REST del dashboard, sus cálculos y el acceso (127.0.0.1 o token)
├── prueba           Generador de datos de prueba (solo con el perfil prueba y la base gastito_prueba)
└── config           Propiedades y reloj en hora de Argentina
```

El resto del repositorio:

```
├── src/main/resources
│   ├── application.yml      Configuración y perfiles (dev, postgres)
│   ├── db/migration/        Migraciones de Flyway (esquema y datos iniciales)
│   ├── prompts/             Instrucciones para la IA y para Whisper, editables sin tocar código
│   └── static/              El dashboard: index.html, css/ y js/ (sin framework)
├── src/test                 Tests con JUnit 5 (H2 en memoria, MockMvc, WireMock)
├── scripts/iniciar.ps1      Arranque con chequeos previos (lo llama iniciar.bat)
├── scripts/generar-datos-prueba.ps1  Datos de prueba desde cero (lo llama generar-datos-prueba.bat)
├── docs/pedidos/            Los pedidos de cada etapa con los que se fue construyendo el bot
├── docs/mejoras-futuras.md  Ideas anotadas para más adelante
├── docs/prototipo-dashboard.html  El prototipo visual del dashboard
├── docker-compose.yml       PostgreSQL para el perfil postgres
└── mvnw, mvnw.cmd           Maven Wrapper
```
