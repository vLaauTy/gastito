# Pedido para Claude Code: Etapa 3 (fotos de tickets)

Pegale a Claude Code todo lo que está debajo de la línea.

---

Leé `CLAUDE.md` antes de empezar. Vamos con la **Etapa 3: fotos de tickets**. Armá un plan y mostrámelo antes de tocar código. Todo tiene que correr y testearse sin Docker en Windows; los cambios de base van en una migración nueva.

## Qué tiene que hacer

Le saco una foto al ticket, se la mando al bot, y me responde con la tarjeta de confirmación de siempre, más el detalle de lo que leyó:

```
🧾 Ticket · Supermercado Día
📅 Sáb 18/10 17:20
🛒 Leche 1 L ×2 ........ $2.900
   Yerba 500 g ......... $3.900
   Galletitas .......... $1.250
💰 Total $8.050
💳 Débito
🏷 Supermercado
```

Con Guardar / Editar / Cancelar, igual que un gasto por texto o audio.

## Pasos

1. **Recibir fotos** (`photo`, tomando la de mayor resolución) y también imágenes mandadas como archivo (`document` con tipo imagen), solo del usuario permitido. Si la foto viene con texto (caption), usalo como pista extra: "efectivo", "con la de Galicia", "es de la juntada".
2. **Achicar la imagen antes de mandarla**: lado más largo de 1568 px como máximo, en JPEG. Un ticket se lee igual de bien y cuesta menos tokens.
3. **Leer el ticket con Haiku** (visión), en un paquete nuevo `ar.gastito.ticket`. Verificá en la documentación oficial que el modelo configurado acepta imágenes. Pedile una respuesta estructurada (JSON) con:
   - `esTicket` (false si la foto no es un ticket o no se puede leer),
   - `comercio`, `fecha`, `hora`,
   - `items`: descripción, cantidad, precio unitario y subtotal de cada uno,
   - `descuentos` (promociones, 2x1, descuentos con tarjeta) y `total`,
   - `medioPago` si figura impreso ("DEBITO", "VISA", "MERCADO PAGO", "EFECTIVO"),
   - `categoria` sugerida, con las candidatas y la confianza, igual que con el texto.
   - El prompt va en `src/main/resources/prompts/leer-ticket.txt`. Tiene que conocer los tickets argentinos: separador de miles con punto, decimales con coma, "Factura B / C", líneas de IVA que no son productos, y fechas en formato dd/mm/aa.
4. **Validar en Java, no confiar a ciegas en la IA** (regla 2 de `CLAUDE.md`):
   - El monto del gasto es el **total impreso** del ticket.
   - Sumá los items menos los descuentos y comparalo con el total. Si la diferencia es de más de $10, mostrá en la tarjeta: "⚠️ Los productos suman $X y el total dice $Y. Revisalo antes de guardar."
   - Si no se pudo leer el total, no guardes nada: pedime una foto más clara, con el ticket entero y sin reflejos.
   - Si la fecha del ticket no es la de hoy, usá la del ticket.
5. **Guardar el detalle por producto** en una tabla nueva `movimiento_item` (movimiento, descripción, cantidad, precio unitario, subtotal, orden). Los descuentos van como items con subtotal negativo.
6. **Guardar la foto** en disco, en `data/tickets/AAAA/MM/<id-del-movimiento>.jpg` (la carpeta `data/` ya está en el `.gitignore`), y la ruta en el movimiento. El movimiento queda con `origen = TICKET`.
7. **Tarjeta de confirmación:** mostrá hasta 8 productos y "…y N más" si hay más. Si los productos no entran en un mensaje, mostrá solo el total y un resumen.
8. **Editar:** los mismos campos que hoy (categoría, monto, medio de pago, fecha, descripción). La descripción por defecto es el nombre del comercio. Editar los productos uno por uno queda para más adelante.
9. **Uso de IA:** registrar en `uso_ia` con propósito `TICKET`.
10. **Dashboard:**
    - En Movimientos, el origen "Ticket" con su etiqueta, y las filas con ticket se pueden desplegar para ver los productos y un link **Ver foto**.
    - Un endpoint `GET /api/movimientos/{id}/foto` que sirva la imagen, y `GET /api/movimientos/{id}/items` con el detalle.
11. **Errores** con mensajes claros: foto que no es un ticket, foto borrosa, ticket sin total, imagen demasiado grande (más de 10 MB) o IA no disponible.

## Privacidad

Los tickets pueden traer los últimos números de la tarjeta, el CUIT o el nombre del cajero. No guardes nada de eso en la base: solo comercio, fecha, productos, total y medio de pago. La foto queda solo en mi PC.

## Tests

Sin Docker ni llamadas reales a la API:
- La validación: total que coincide, total que no coincide, ticket sin total, descuentos, fecha distinta de hoy.
- El parseo de la respuesta de la IA (con JSON de ejemplo, incluido uno mal formado).
- El achicado de la imagen (una imagen grande queda dentro del límite y conserva la proporción).
- Que una foto termina en el mismo flujo de confirmación que el texto y el audio.
- Los endpoints de foto e items del dashboard.

## Al terminar

Corré los tests, levantá la aplicación con el perfil `postgres` y decime cómo probar: un ticket de supermercado con varios productos, uno de kiosco cortito, una foto que no sea un ticket y una foto borrosa. Contame cuánto costó en promedio cada lectura según `uso_ia`. Commits chicos por cada paso, y al final subí todo a GitHub.

## Ajustes acordados antes de empezar

- **Foto de un ticket cancelado:** se borra del disco. Solo quedan las fotos de los tickets guardados.
- **Qué foto se guarda:** la achicada (la misma que lee la IA), no la original.
- **"Es de la juntada" en el texto de la foto:** no se marca nada; las juntadas siguen en la etapa 6.
- **Tickets muy largos:** si achicados a 1568 px quedan ilegibles, el bot pide sacarlos en dos partes. El lado
  máximo queda configurable (`gastito.ticket.lado-maximo`).
- **Privacidad:** además del prompt, Java borra de la descripción y de los productos cualquier secuencia de 6 o
  más dígitos (tarjeta, CUIT, códigos). La foto viaja a la API de Anthropic para leerla y después queda solo en
  `data/tickets/`.

## Agregado: lo que aprendimos de un ticket real

Con un ticket real de Día (22/09/2026; lectura esperada en `src/test/resources/tickets/dia-2026-09-22-esperado.json`):

- **Código de barras:** se guarda en `movimiento_item.codigo_barras` (opcional), solo si es un EAN-8 o EAN-13
  con el dígito verificador correcto; si no, se descarta. No es un dato personal: identifica un producto.
- **Privacidad:** no se borran números por su largo. La protección principal es que la respuesta de la IA solo
  tiene campos para lo que se guarda: el CUIT, la cajera, la caja y el número de ClubDía no tienen dónde ir. El
  filtro (tarjetas, CUIT) se aplica solo a los textos libres.
- **Sucursal:** se guarda en el movimiento como texto opcional ("5601"). En la etapa 7 permite cruzar el ticket
  con `DIA TIENDA 5601` del resumen de la tarjeta.
- **Descripción:** de cada producto se guarda la original (como está impresa) y la legible.
- **Números:** en un mismo ticket conviven los dos formatos (coma decimal en los importes y punto en el TOTAL).
- **Tres controles:** productos = subtotal impreso, descuentos = descuentos impresos y subtotal + descuentos =
  total. Los descuentos ("20% ClubDía") van debajo de cada producto, en el orden impreso; el "Ahorro ClubDía" es
  informativo y no se resta de nuevo.
- **Ticket arrugado:** si las cuentas no cierran, la tarjeta dice qué línea no se pudo leer.
- **"TARJETA" sin aclarar** débito o crédito queda sin medio de pago.
- **Prueba con la foto real:** `LecturaTicketRealIT` lee la foto de `data/tickets-prueba/` (fuera del repo) y la
  compara con la lectura esperada. Si la foto no está, el test se saltea.
