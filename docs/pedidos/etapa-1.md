# Pedido para Claude Code: Etapa 1

Copiá y pegá todo lo que está debajo de la línea en Claude Code, abierto en la carpeta del repositorio `gastito` (con el `CLAUDE.md` ya guardado en la raíz).

---

Leé `CLAUDE.md` antes de empezar. Vamos a construir la **Etapa 1** de Gastito: el bot de Telegram funcionando con mensajes de texto.

Antes de escribir código, armá un plan y mostrámelo para que lo apruebe.

## Qué tiene que hacer al terminar

Yo le escribo al bot algo como:
- `kiosco 1500`
- `3700 en pan de pancho y papas, efectivo`
- `pagué 12000 de nafta con la tarjeta`

y el bot me responde con lo que entendió:

> Almacén / Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42
> ¿Lo guardo así?
> [Guardar] [Editar] [Cancelar]

Si toco **Guardar**, se guarda y me responde con el total del mes en esa categoría (calculado con SQL). Si toco **Cancelar**, lo descarta. **Editar** en esta etapa puede limitarse a pedirme que reescriba el mensaje.

## Pasos

1. **Proyecto base:** Spring Boot 3 con Maven y Java 21. Dependencias: web, data-jpa, validation, flyway, H2, driver de PostgreSQL, TelegramBots (long polling), SDK de Anthropic para Java. Verificá las versiones estables actuales.
2. **Configuración:** `application.yml` con perfiles `dev` (H2 en archivo, por defecto) y `postgres`. Lectura del `.env` con `spring.config.import`. Agregá un `docker-compose.yml` con PostgreSQL para el perfil `postgres`, aunque todavía no lo usemos. Asegurate de que `.env` y `data/` estén en `.gitignore` y creá `.env.example`.
3. **Base de datos (Flyway V1):** tablas `categoria`, `medio_pago`, `cuenta`, `movimiento`, `alias_comercio` y `uso_ia`, con los datos iniciales de `CLAUDE.md`. `movimiento` tiene: id, fecha y hora, monto (BigDecimal), descripción, tipo, categoría, medio de pago, cuenta (opcional), marca juntada, origen (`TEXTO` por ahora; después `AUDIO`, `TICKET`, `AVISO`, `RESUMEN`), estado (`PENDIENTE`, `CONFIRMADO`, `CANCELADO`) y fecha de creación. Pensá en dejar lugar para cuotas (cuota actual / total) aunque no se usen todavía.
4. **Bot de Telegram:**
   - Ignorar mensajes de cualquier usuario que no sea `TELEGRAM_ALLOWED_USER_ID`.
   - Comandos: `/start` (saludo y ejemplos de uso), `/ayuda`, `/hoy` (gastos de hoy y total), `/mes` (total del mes por categoría).
5. **Interpretación del texto, en dos niveles:**
   - Primero un **parser simple sin IA** para los casos obvios (`kiosco 1500`, `1500 kiosco`): número + palabra que coincida con una categoría. Si alcanza, no se llama a la API.
   - Si no alcanza, se llama a **Haiku** pidiéndole una respuesta estructurada (JSON con monto, descripción, categoría, forma de pago, cuenta y fecha), pasándole la lista real de categorías y formas de pago de la base. Validá la respuesta: si la categoría no existe, usá Otros; si falta el monto, preguntame.
   - El prompt va en `src/main/resources/prompts/interpretar-gasto.txt`.
   - Fechas relativas ("ayer", "el sábado") resueltas con la zona horaria `America/Argentina/Buenos_Aires`.
6. **Confirmación:** el movimiento interpretado se guarda como `PENDIENTE` y se muestra con botones inline. Guardar lo pasa a `CONFIRMADO`; Cancelar, a `CANCELADO`.
7. **Uso de la IA:** cada llamada registra tokens y costo en `uso_ia`. Agregá un comando `/costos` que muestre lo gastado en IA en el mes, en dólares.
8. **Tests:** del parser simple (varios formatos), de la validación de la respuesta de la IA y del cálculo de costo.
9. **README:** cómo correr el proyecto en perfil `dev`, y cómo cambiar a `postgres` cuando Docker esté listo.

## Fuera de esta etapa

Audios, fotos, panel web, resúmenes, juntadas, ingresos y resumen de tarjeta. No los implementes todavía.

## Al terminar

Corré los tests, levantá la aplicación en perfil `dev` y decime cómo probarla desde Telegram. Hacé commits chicos por cada paso.
