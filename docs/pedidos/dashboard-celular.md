# Pedido: dashboard usable desde el celular (Android)

Leé `CLAUDE.md`. Trabajá nativo, commits chicos, push al final y reporte de 10 líneas como máximo. Solo HTML/CSS/JS del dashboard: no cambies la lógica ni los endpoints.

Hoy el dashboard se adapta lo mínimo (menú arriba y tablas con scroll horizontal). Quiero que en un celular de unos 390 px de ancho se use cómodo, sin scroll horizontal. Lo mismo va a servir más adelante para la Mini App de Telegram.

## Diseño en pantallas de menos de 720 px

- **Navegación abajo**, fija, con íconos y texto corto (Resumen, Movimientos, Ingresos, Fijos, Más). Las secciones que no entren van en "Más".
- **Tablas → tarjetas:** cada fila pasa a ser una tarjeta con lo importante arriba (descripción y monto) y lo secundario abajo (fecha, categoría, medio de pago). Al tocarla se despliega el detalle (productos del ticket, conceptos del recibo).
- **Botones y campos** de al menos 44 px de alto, y los inputs de montos con teclado numérico (`inputmode="decimal"`).
- **Gráficos** al ancho de la pantalla, con etiquetas legibles.
- **Checklist de fijos y calculadora de sueldo**: en una columna.
- Modo oscuro como el actual.

## Verificación

Revisalo con el navegador headless a 390 × 844 y a 360 × 800, en cada sección, con los datos de prueba: sin scroll horizontal, nada cortado y textos legibles. Mandame capturas de Resumen, Movimientos y Fijos a ese tamaño.

## Acceso desde el celular

**No cambies** que el dashboard escuche solo en `127.0.0.1`. Agregá al README cómo verlo desde el celular con **Tailscale** (`tailscale serve` apuntando a `http://127.0.0.1:8090`), que lo hace accesible solo desde mis dispositivos, sin abrirlo a internet ni a la red local.
