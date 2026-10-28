# Pedido: Etapa 8 (recibos de sueldo y costos de la IA)

Leé `CLAUDE.md` antes de empezar. Trabajá **nativo** (sin subagentes ni el flujo de Superpowers): mostrame un resumen corto del enfoque en el chat y arrancá. Cambios de base en migraciones nuevas, todo se prueba con el perfil `prueba`, commit por parte y push al terminar cada una.

**La etapa 7 (resumen de la tarjeta y avisos automáticos) queda postergada.** No la implementes, pero no tomes decisiones que la bloqueen.

## Parte A: recibos de sueldo

Le mando al bot mi recibo de sueldo y lo registra como ingreso con todo el detalle.

**Entrada:**
- **PDF** (como `document`): si tiene texto, extraelo con PDFBox y mandale **el texto** a Haiku para interpretarlo (más barato que una imagen). Si no tiene texto (escaneado), usá visión, igual que con los tickets.
- **Foto** del recibo impreso: visión, con el mismo achicado de imagen que los tickets.
- Para distinguir un recibo de un ticket, la IA devuelve el tipo de documento (`TICKET`, `RECIBO_SUELDO`, `OTRO`) en la misma llamada; no hagas una llamada extra.
- Prompt en `src/main/resources/prompts/leer-recibo.txt`. Propósito `RECIBO` en `uso_ia` (agregalo a los propósitos).

**Qué se extrae:** empleador, período (mes y año), fecha de pago, tipo (sueldo mensual, aguinaldo/SAC, vacaciones, liquidación final) y cada **concepto** con su importe y su grupo:
- Remunerativo (básico, antigüedad, presentismo, horas extra…).
- No remunerativo.
- Descuento (jubilación, ley 19.032/PAMI, obra social, sindicato, impuesto a las ganancias…).
- Los totales impresos: bruto, total de descuentos y **neto**.

**Validación en Java** (la IA no calcula):
- Remunerativos + no remunerativos − descuentos = neto impreso, con tolerancia de $1. Si no cierra, avisá en la tarjeta qué no cierra.
- Si no se lee el neto, no se guarda: pedime un archivo o una foto más clara.

**Tarjeta de confirmación:**
```
💼 Recibo · Octubre 2026 · <Empleador>
Bruto ........... $1.250.000
Descuentos ...... −$212.500
  Jubilación 11%  −$137.500
  Obra social 3%  −$37.500
  …
💰 Neto ......... $1.037.500
```
Botones: Guardar / Editar / Cancelar.

**Al guardar:**
- Se crea el `INGRESO` por el **neto**, con la clase que corresponda (sueldo o aguinaldo, las mismas de `/sueldo` y `/aguinaldo`), en el mes del período.
- Si ya cargué a mano el sueldo de ese mes (con `/sueldo`), no lo dupliques: preguntá **"Ya cargaste el sueldo de octubre ($1.030.000). ¿Lo reemplazo con el del recibo?"**.
- Tablas `recibo_sueldo` (movimiento, empleador, período, tipo, bruto, descuentos, neto, ruta del archivo) y `recibo_concepto` (recibo, descripción, grupo, importe, orden).
- El archivo se guarda en `data/recibos/AAAA/MM/` (ya está en el `.gitignore`).

**Privacidad:** no guardes en la base CUIL, CBU, número de legajo ni domicilio. El archivo queda solo en mi PC. **Nunca subas un recibo real al repo:** los tests usan recibos inventados.

**Dashboard (sección Ingresos):**
- Cada ingreso que vino de un recibo se puede desplegar para ver los conceptos y un link para **Ver recibo**.
- Un gráfico de los últimos 12 meses: bruto, neto y descuentos.
- Variación del neto contra el mes anterior y contra el mismo mes del año anterior (si hay datos).

**Preguntas:** herramienta nueva `recibosSueldo(desde, hasta)`. Ejemplos: "¿cuánto me descontaron de ganancias este año?", "¿cuánto aumentó mi sueldo desde enero?".

## Parte B: pantalla de costos de la IA

Todo sale de la tabla `uso_ia`, que ya existe. Los montos van en **USD**, que es como cobra Anthropic.

**Dashboard, sección nueva "Costos IA":**
- Total del mes actual y del anterior, y proyección a fin de mes (lo gastado ÷ días transcurridos × días del mes).
- Desglose por propósito (texto, audio, ticket, recibo, resumen, consulta): cantidad de llamadas, tokens de entrada y salida, costo total y **costo promedio por llamada**.
- Gráfico de costo por día del mes.
- Historial de los últimos 6 meses.
- Las 10 llamadas más caras del mes (fecha, propósito, tokens, costo), para ver qué conviene optimizar.

**Tope mensual:**
- `gastito.ia.tope-mensual-usd` en `application.yml` (por defecto 2).
- Cuando el mes llega al 80%, el bot avisa una sola vez: "La IA lleva USD 1,62 este mes (81% del tope de USD 2)". Al 100%, otro aviso. **No bloquea** nada, solo avisa.

**Bot:** `/costos` muestra el total del mes, la proyección y el desglose por propósito en pocas líneas.

**Precios:** el costo de cada llamada se calcula al guardarla con los precios de `application.yml` y queda fijo. Si cambian los precios, el historial no se recalcula.

## Tests

- Recibo: validación que cierra y que no cierra, sin neto, aguinaldo, reemplazo del sueldo cargado a mano, parseo de la respuesta de la IA (incluida una mal formada) y extracción de texto de un PDF de prueba inventado.
- Costos: totales por propósito, promedio por llamada, proyección (primer día del mes, mitad, último día) y avisos de tope (que se manden una sola vez).
- Endpoints nuevos y la herramienta `recibosSueldo`.

## Al terminar

Suite completa una vez, regenerá los datos de prueba (6 recibos mensuales con un aguinaldo y un aumento, y registros de `uso_ia` de 3 meses), commit y push. Reporte de 10 líneas como máximo con cómo probarlo.
