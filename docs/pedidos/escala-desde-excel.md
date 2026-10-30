# Pedido: cargar la escala de sueldo desde la hoja del sindicato (foto o Excel)

Leé `CLAUDE.md`. Trabajá nativo, tests, commit y push. Reporte de 10 líneas como máximo.

## Cómo es la escala real

El sindicato (SOM) publica una hoja "Recomposición salarial", categoría **Oficial de primera, jornada completa**, con **una columna por mes** (por ejemplo ago-26 … dic-26). Cada columna es el **mes trabajado** (período): los valores de oct-26 se cobran alrededor del 5/11. Coincide con el período del sueldo que ya existe.

Filas y cómo se usan:

| Fila de la hoja | Concepto en Gastito |
|---|---|
| BASICO | Sueldo básico (remunerativo) |
| PRESENTISMO | Presentismo (remunerativo) |
| A.I.S. | Asignación integración social (remunerativo) |
| ASIG. EXT. NO REMUNERATIVA **6%** | Asignación extraordinaria (no remunerativo). **Usar la fila del 6%**: es el valor que se cobra neto. La fila sin "6%" se ignora. |
| VIATICOS | Viático (no remunerativo) |
| HORAS NORMALES | Valor hora normal |
| HORAS AL 50 % | Valor hora al 50% |
| HORAS AL 100% | Valor hora al 100% |
| VALORES NETOS | No es un concepto: se usa como **control** (ver abajo) |
| ART. 13, ART. 28, ART. 13 Y 28 | Se ignoran (no me corresponden) |

**No vienen en la hoja** y se mantienen de la escala anterior: el adicional **CCT planta nuclear** (lo saco de otra fuente; hoy $98.985) y los **porcentajes de descuento**. Avisá cuáles se mantuvieron ("CCT planta nuclear: se mantiene $98.985").

## Cambio en el cálculo

Los valores de hora al 50% y al 100% de la hoja **no siempre son exactamente** hora normal × 1,5 y × 2 (en nov-26 difieren unos centavos). Si la escala tiene el valor de la hora al 50% o al 100%, el cálculo usa **ese valor**; si no, usa hora normal × factor. Las nocturnas siguen siendo hora normal × 8/60.

## Control con "Valores netos"

En la hoja, **Valores netos = (básico + presentismo + A.I.S.) × 0,80 + asignación 6% + viáticos** (lo verifiqué en las 5 columnas, diferencia de centavos). Al importar, calculá ese mismo número con los porcentajes de descuento de la escala y comparalo con la fila de la hoja: si difiere más de $5, avisá ("Con tus descuentos da $X y la hoja dice $Y: revisá los porcentajes").

## Formas de cargarla

1. **Foto de la hoja** (por el bot): Haiku con visión la lee **una sola vez** y devuelve un JSON por columna. Propósito nuevo `ESCALA` en `uso_ia`. Prompt en `prompts/leer-escala.txt`, con los formatos argentinos ($ 1.023.863,86 y algún valor con punto decimal como "$ 1.071,978,46" que hay que leer con cuidado). Java valida: cada número con formato de plata válido y el control de "Valores netos" de cada columna.
2. **Excel** (web o bot): el mismo formato de la hoja (filas con el nombre en la columna A y **una columna por mes** con el mes en el encabezado), o mi plantilla de dos columnas (nombre y valor, un solo mes). Apache POI, buscando por el texto de la fila (sin importar mayúsculas, acentos, espacios ni puntos: "A.I.S." = "AIS"), sin IA. Las celdas con fórmulas se ignoran.
3. **Descargar plantilla** desde la web con el formato de la hoja del sindicato y la escala vigente.

Los nombres de fila que reconoce cada concepto se guardan como **alias del concepto** (editables), no hardcodeados.

## Antes de guardar

Mostrá un resumen por mes con los cambios contra la escala anterior y el resultado del control:
```
📄 Escalas desde la hoja del sindicato
oct-26  Básico $558.154 · Presentismo $105.822 · Hora 50% $5.445,52 … ✅ control
nov-26  Básico $608.101 (+8,9%) · Presentismo $107.516 (+1,6%) … ✅ control
dic-26  …
Se mantienen: CCT planta nuclear $98.985, descuentos 11 + 3 + 3 + 3 %
```
Botones: **Guardar todas** / **Elegir meses** / **Cancelar**. Si ya existe una escala para alguno de esos meses, mostrá qué cambia y preguntá si la reemplaza.

## Tests

Con una foto y un Excel de prueba **inventados** (nunca los reales): lectura de varias columnas, fila del 6% elegida sobre la otra, filas ignoradas (Art. 13/28), conceptos que se mantienen, valores de hora explícitos usados en el cálculo, control de "Valores netos" que da bien y que falla, y reemplazo de una escala existente.
