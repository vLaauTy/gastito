# Pedido: rediseño del dashboard (celular e íconos)

Leé `CLAUDE.md`. Trabajá nativo, commits chicos, push al final y reporte de 10 líneas como máximo. Solo HTML/CSS/JS del dashboard, más la columna `icono` de categorías: no cambies la lógica ni los demás endpoints.

Hoy el dashboard se adapta lo mínimo (menú arriba y tablas con scroll horizontal). Quiero que en un celular de unos 390 px de ancho se use cómodo, sin scroll horizontal. Lo mismo va a servir más adelante para la Mini App de Telegram.

## Diseño en pantallas de menos de 720 px

- **Navegación abajo**, fija, con íconos y texto corto (Resumen, Movimientos, Ingresos, Fijos, Más). Las secciones que no entren van en "Más".
- **Tablas → tarjetas:** cada fila pasa a ser una tarjeta con lo importante arriba (descripción y monto) y lo secundario abajo (fecha, categoría, medio de pago). Al tocarla se despliega el detalle (productos del ticket, conceptos del recibo).
- **Botones y campos** de al menos 44 px de alto, y los inputs de montos con teclado numérico (`inputmode="decimal"`).
- **Gráficos** al ancho de la pantalla, con etiquetas legibles.
- **Checklist de fijos y calculadora de sueldo**: en una columna.
- Modo oscuro como el actual.

## Mejoras visuales (escritorio y celular)

**Íconos SVG en lugar de emojis en la web.** El bot de Telegram sigue con emojis (Telegram no muestra SVG).
- Usá **Lucide** (licencia ISC), con una copia local de solo los íconos que se usan (un sprite `icons.svg` o los SVG sueltos en `static/`). Nada desde un CDN: tiene que andar sin internet.
- **Categorías:** columna nueva `icono` en `categoria` (migración nueva; el `emoji` queda para el bot). Sugerencia: Kiosco `store`, Supermercado `shopping-cart`, Salidas `party-popper`, Comida afuera / delivery `utensils`, Productos para la casa `home`, Servicios `zap`, Transporte / Moto `bike`, Suscripciones `repeat`, Seguros `shield`, Salud / farmacia `pill`, Ropa `shirt`, Impuestos y cargos de tarjeta `receipt`, Otros `circle-ellipsis`. Editable desde el dashboard.
- **Un color por categoría**, el mismo en listas, chips y gráficos, con buen contraste en modo claro y oscuro.
- **Medios de pago:** `banknote` (efectivo), `credit-card` (débito y crédito), `landmark` (transferencia), `qr-code` (QR). Para Galicia y Mercado Pago, sin logos de las marcas: un círculo de color con la inicial.
- **Estados** (falta, cargado, este mes no, aviso): ícono SVG + color + texto. Que no dependa solo del color.

**Detalles de diseño:**
- Montos con `font-variant-numeric: tabular-nums` para que las columnas queden alineadas.
- **Estados vacíos** con ícono, una frase y qué hacer ("Todavía no hay gastos este mes. Mandale uno al bot.").
- Esqueletos de carga en lugar de pantallas en blanco, y avisos cortos (toasts) al guardar o borrar desde la web.
- Gráficos de Chart.js con los colores de las categorías y tooltips en pesos ($ 12.345).

## Verificación

Revisalo con el navegador headless a 390 × 844 y a 360 × 800, en cada sección, con los datos de prueba: sin scroll horizontal, nada cortado y textos legibles. Mandame capturas de Resumen, Movimientos y Fijos a ese tamaño, y de Resumen a 1366 × 768, en modo claro y oscuro.

## Acceso desde el celular

**No cambies** que el dashboard escuche solo en `127.0.0.1`. Agregá al README cómo verlo desde el celular con **Tailscale** (`tailscale serve` apuntando a `http://127.0.0.1:8090`), que lo hace accesible solo desde mis dispositivos, sin abrirlo a internet ni a la red local.
