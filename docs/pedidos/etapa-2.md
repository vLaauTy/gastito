# Pedido para Claude Code: Etapa 2 (audios)

Antes de pegarlo, tiene que estar funcionando el servidor de Whisper en la VM (ver los pasos manuales que te pasé) y estas dos líneas agregadas al `.env`:

```
WHISPER_URL=http://localhost:9000
WHISPER_API_KEY=la clave que te dio whisper_manage
```

Pegale a Claude Code todo lo que está debajo de la línea.

---

Leé `CLAUDE.md` antes de empezar. Vamos con la **Etapa 2: audios**. Armá un plan y mostrámelo antes de tocar código.

## Contexto

- La transcripción la hace un servidor de Whisper que corre en Docker dentro de una VM de Ubuntu, accesible desde Windows en `http://localhost:9000`. Tiene una API compatible con la de OpenAI: `POST /v1/audio/transcriptions`, `multipart/form-data`, con los campos `file`, `model` (mandá `whisper-1`), `language` (`es`), `prompt` y `response_format` (`json`). Requiere el header `Authorization: Bearer <WHISPER_API_KEY>`.
- Acepta los audios de Telegram (`.oga` / OGG Opus) directamente: no hace falta ffmpeg en Windows.
- La URL y la clave vienen de `WHISPER_URL` y `WHISPER_API_KEY` en el `.env`. Nunca las escribas en el repo ni en los logs.
- Todavía no tengo Docker en Windows: los tests no pueden depender de Docker ni del servidor real.

## Qué tiene que hacer

Mantengo apretado el micrófono en Telegram, digo "gasto 3700 en pan de pancho y papas, efectivo", y el bot me responde con la misma tarjeta de confirmación que un mensaje de texto, más una línea arriba con lo que entendió:

> 🎙 Escuché: "gasto 3700 en pan de pancho y papas, efectivo"

## Pasos

1. **Recibir mensajes de voz** (`voice`) y también archivos de audio (`audio`), solo del usuario permitido, como el resto.
2. **Descargarlos** con la API de Telegram (`getFile`) en memoria o en un archivo temporal que se borre al terminar.
3. **Transcribir** con un cliente `ClienteTranscripcion` en un paquete nuevo `ar.gastito.audio`:
   - `language=es`.
   - En `prompt` mandá un texto corto con los nombres de mis categorías, medios de pago y cuentas (Galicia, Mercado Pago, débito, QR…), para que Whisper escriba bien esas palabras.
   - Timeout configurable (por defecto 60 segundos).
   - Mientras transcribe, mandá la acción de chat "escribiendo" (`sendChatAction`) para que vea que está trabajando.
4. **Reusar el mismo flujo del texto:** la transcripción pasa por el parser simple y, si hace falta, por Haiku, exactamente igual que un mensaje escrito. El movimiento queda con `origen = AUDIO`. No dupliques lógica.
5. **Mostrar lo que escuchó** en la tarjeta de confirmación (la línea "🎙 Escuché: …"), así si Whisper entendió mal lo veo antes de guardar.
6. **Límites y errores**, con mensajes claros para mí:
   - Audios de más de 60 segundos (configurable): no los proceso y te pido uno más corto.
   - Servidor de Whisper apagado o sin respuesta: "No pude transcribir el audio, ¿la VM está prendida? Mientras tanto mandámelo escrito."
   - Transcripción vacía o sin un gasto: te lo digo y te muestro lo que escuché.
7. **Configuración** en `application.yml`: URL, timeout, duración máxima, idioma. La clave solo desde el `.env`.
8. **Uso de IA:** Whisper es local y no cuesta nada, así que no se registra en `uso_ia`. La llamada a Haiku que interpreta el texto transcripto sí se registra, con propósito `AUDIO`.
9. **Tests** sin Docker ni servidor real: simulá el servidor de Whisper con un servidor HTTP falso (MockWebServer o WireMock en modo embebido) para probar el pedido que se arma, el parseo de la respuesta, el timeout y los errores. Probá también que un audio termina en el mismo flujo de confirmación que el texto.
10. **README:** cómo levantar el servidor de Whisper en la VM y qué variables van en el `.env`.

## Al terminar

Corré los tests, levantá la aplicación con el perfil `postgres` y decime cómo probar: un audio con un gasto claro, uno dudoso, y qué pasa si apago el contenedor de Whisper. Commits chicos por cada paso.
