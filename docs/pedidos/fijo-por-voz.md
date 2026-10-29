# Pedido: dar de alta fijos en lenguaje natural (texto y audio)

Leé `CLAUDE.md`. Trabajá nativo, tests, commit y push. Reporte de 5 líneas como máximo.

## El problema

Mandé este audio: "agregame un gasto fijo que es el seguro de la moto, que es aproximadamente 32 mil pesos, que es todos los meses y lo pago con tarjeta de crédito". El bot no lo dio de alta: me contestó que lo escriba con el formato del comando `/fijo`. La idea es no tener que escribir comandos.

## Lo que quiero

1. **Intención nueva `ALTA_FIJO`** en la interpretación (la misma llamada a la IA de siempre, sin llamadas extra). Frases como "agregá un fijo…", "sumá un gasto fijo…", "tengo que pagar todos los meses…", "anotá que pago … por mes". Tolerá errores de transcripción de Whisper ("arreglame un gasto fijo" = "agregame").
2. La IA extrae lo que haya: nombre, monto aproximado, frecuencia (mensual / bimestral), medio de pago, día del mes y categoría sugerida. "Tarjeta de crédito" se resuelve a la tarjeta que existe (MasterCard Gold de Galicia). Java valida y arma el fijo.
3. **Tarjeta de confirmación** con lo que entendió:
   ```
   📌 Nuevo gasto fijo
   Seguro moto · ≈ $32.000 · mensual
   💳 Tarjeta de crédito · 🏷 Seguros
   📅 Día: sin definir
   ```
   Botones: **Guardar** / **Editar** / **Cancelar**.
4. **Lo que falte se pregunta con botones, nunca pidiendo el formato del comando:**
   - Día: botones con días frecuentes (1, 5, 10, 15, 20, 25), **"Otro"** (escribo el número) y **"No sé"**. Con "No sé" se guarda sin día: aparece en el checklist del mes pero sin recordatorio de vencimiento.
   - Monto: si no lo dije, lo pide como texto (con "No sé" → sin monto de referencia).
   - Medio de pago y categoría: los mismos botones que en un gasto.
5. El nombre se normaliza corto ("Seguro moto") y la frase original queda como **alias** ("seguro de la moto"), para reconocerlo después cuando diga que lo pagué.
6. Si ya existe un fijo con nombre o alias parecido, avisá: "Ya tenés el fijo Seguro moto ($30.000). ¿Actualizo el monto o es otro?".
7. `/fijo` sigue funcionando como atajo, pero ningún mensaje del bot le pide al usuario que use un formato de comando.

## Tests

Interpretación con la IA simulada (texto y transcripción con error), datos faltantes con sus botones (día "No sé", "Otro"), resolución de "tarjeta de crédito", alias generado, fijo duplicado.
