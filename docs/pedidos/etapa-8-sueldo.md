# Pedido: Etapa 8, cambio en la Parte A (calculadora de sueldo y control de recibos)

Leé `CLAUDE.md` y `docs/pedidos/etapa-8.md`. **Este pedido reemplaza la Parte A** de `etapa-8.md`; la Parte B (costos de la IA) queda igual. Si ya implementaste la Parte A, adaptala a esto; si no, implementala directamente así. Trabajá nativo, migraciones nuevas, perfil `prueba`, commit por parte y push al terminar.

## Cómo es mi sueldo en la realidad

1. A fin de mes **calculo** cuánto voy a cobrar (hoy lo hago en un Excel).
2. **Cobro** el sueldo (lo cargo con `/sueldo` o por mensaje).
3. **Una semana después** me dan el recibo. El recibo no es para registrar el ingreso: es para **guardarlo y ver si hay algo raro**.

## Parte A1: calculadora de sueldo

Reproducí mi Excel, pero de forma genérica (los conceptos cambian con las paritarias y el convenio, no los hardcodees en Java).

**Conceptos configurables** (tabla `concepto_sueldo`), cada uno con un tipo:
- `REMUNERATIVO_FIJO`: monto fijo del mes (básico, presentismo, adicional CCT planta nuclear, asignación integración social).
- `HORAS`: horas × valor hora normal × factor. Mis factores: horas al 50% → 1,5; horas al 100% → 2; horas nocturnas → 8/60 (cada hora nocturna suma 8 minutos de hora normal).
- `NO_REMUNERATIVO`: no lleva descuentos (viático, asignación extraordinaria SOM).
- `DESCUENTO_PORCENTAJE`: porcentaje sobre el bruto remunerativo (jubilación 11%, obra social 3%, ley 19.032 3%, sindicato 3%).

**Escala con vigencia:** los montos fijos, el valor de la hora normal y los porcentajes forman una **escala** con fecha "desde". Cuando cambia la paritaria cargo una escala nueva copiando la anterior y editando los valores (en la web). El historial de escalas se conserva.

**Cálculo (Java, BigDecimal, redondeo a centavos):**
- Bruto remunerativo = fijos remunerativos + conceptos por horas.
- Cada descuento = bruto × porcentaje.
- Neto de haberes = bruto − descuentos.
- **Neto total = neto de haberes + no remunerativos.**

Caso de test (valores inventados, no uses los de mi Excel en el repo): básico 500.000, presentismo 100.000, CCT 90.000, integración 60.000, hora normal 3.000, 10 h al 50%, 6 h al 100%, 30 h nocturnas, viático 300.000, asignación extraordinaria 140.000, descuentos 11 + 3 + 3 + 3 % → bruto **843.000**, descuentos **168.600**, neto de haberes **674.400**, neto total **1.114.400**.

**Cada mes** solo cargo lo variable: horas al 50%, al 100% y nocturnas (y, si hace falta, un ajuste puntual de algún concepto). Se guarda como un **cálculo** del período (mes trabajado) con la escala usada.

**Desde el bot:** "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas" o `/calcular 10 6 30` → tarjeta con bruto, descuentos, no remunerativos y neto total, con Guardar / Editar / Cancelar. `/calculo` muestra el del mes.
**Desde la web (sección Ingresos → Calculadora):** formulario con las horas, resultado en vivo y el detalle por concepto; edición de escalas y conceptos.

## Parte A2: cobro comparado con el cálculo

Cuando cargo el sueldo cobrado, si hay un cálculo para ese período, la tarjeta lo compara: "Calculaste $1.114.400 · Cobraste $1.110.000 (−$4.400, −0,4%)". Si la diferencia supera el 1% (configurable), lo marca con ⚠️. El ingreso se guarda igual, por lo cobrado.

## Parte A3: recibo de sueldo (control, no registro)

Igual que en `etapa-8.md` para la lectura (PDF con texto → PDFBox + texto a Haiku; escaneo o foto → visión; propósito `RECIBO`; privacidad: sin CUIL, CBU, legajo ni domicilio; archivo en `data/recibos/`), **pero con estos cambios:**

- **No crea el ingreso.** Se vincula al sueldo cobrado de ese período. Si todavía no cargué el cobro, el recibo se guarda igual y se vincula cuando lo cargue (o lo vinculo a mano).
- **Control de cosas raras** (lo calcula Java; la IA solo lo redacta en lenguaje claro). Se muestran en la tarjeta y en la web:
  - Neto del recibo distinto de lo cobrado.
  - Cada concepto contra mi cálculo: horas extra pagadas distintas de las que cargué, básico o adicionales distintos de la escala vigente.
  - Porcentajes de descuento distintos de los de la escala (por ejemplo, ley 19.032 al 2,5% en lugar del 3%).
  - Conceptos que estaban el mes anterior y ahora no (por ejemplo, presentismo) y conceptos nuevos.
  - Los totales del recibo que no cierran (remunerativos + no remunerativos − descuentos ≠ neto).
  - Si no hay nada raro: "✅ El recibo coincide con tu cálculo".
- Para comparar, la IA mapea cada línea del recibo a uno de mis conceptos (o "otro"); el resultado del mapeo se guarda para reutilizarlo con los recibos siguientes.

**Web (Ingresos):** por cada mes, una fila con **Calculado · Cobrado · Recibo** y sus diferencias, el detalle desplegable, **Ver recibo**, y el gráfico de 12 meses (bruto, neto, descuentos) que ya estaba pedido.

**Preguntas:** la herramienta `recibosSueldo(desde, hasta)` también devuelve los cálculos y las diferencias.

## Tests

- Calculadora con el caso de arriba, cambio de escala por vigencia y ajuste puntual.
- Comparación cobro vs. cálculo (dentro y fuera de la tolerancia).
- Cada control del recibo (uno por tipo de cosa rara) y el recibo sin nada raro.
- Recibo que llega antes del cobro y se vincula después.

## Datos de prueba

6 meses con escala (y un cambio de paritaria en el medio), cálculo, cobro y recibo; uno de los recibos con una cosa rara (un porcentaje de descuento distinto) y otro sin recibo todavía.

## Al terminar

Suite completa una vez, commit y push. Reporte de 10 líneas como máximo con cómo probarlo.
