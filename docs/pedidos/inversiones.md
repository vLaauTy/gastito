# Pedido: pestaña Inversiones (ahorros invertidos)

Leé `CLAUDE.md`. Trabajá nativo, migración nueva, tests, commit por parte y push. Antes de tocar la base real, `backup.bat`. Reporte de 10 líneas como máximo.

## La idea

Tengo plata invertida (por ejemplo en Cocos, en acciones o CEDEARs) cuyo valor cambia todos los días. No es ingreso del mes ni gasto: son **ahorros**. Quiero verla en una pestaña propia, **Inversiones**, sin que se mezcle con los movimientos del día a día ni con el disponible del mes.

## Modelo

- **Cuenta de inversión** (tipo nuevo de cuenta): por ejemplo "Cocos". Puede tener **posiciones** con nombre libre ("CEDEAR SPY", "FCI money market", "Acción YPF"), o ser una sola bolsa sin detalle.
- **Aporte inicial (ahorro previo):** la plata que ya tengo invertida se carga como **saldo inicial** de la cuenta o de la posición, con fecha. **No es un ingreso**: no suma a los ingresos del mes ni al disponible. Por el bot: "tengo 850.000 invertidos en Cocos, 500.000 en CEDEAR SPY y 350.000 en un FCI".
- **Aportes nuevos:** "pasé 200.000 de Galicia a Cocos" → `ENTRE_CUENTAS` (la plata ya entró como ingreso cuando cobré). "Compré 100.000 de CEDEAR SPY" → mueve dentro de Cocos, de la bolsa a esa posición.
- **Retiros:** "saqué 150.000 de Cocos a Galicia" → `ENTRE_CUENTAS` al revés.
- **Valor actual:** se actualiza a mano, con fecha: "Cocos vale 870.000", "SPY está en 520.000". Se guarda como **valuación** (historial), no como movimiento.
- **Rendimiento** (lo calcula Java): valor actual − (saldo inicial + aportes − retiros), en pesos y en %. Se muestra como ganancia o pérdida, **nunca como ingreso**.

## Pestaña Inversiones (dashboard)

- Arriba: **Total invertido hoy $X** · actualizado hace N días · rendimiento total $Y (Z%).
- Una fila por cuenta y posición: lo que puse, lo que vale, rendimiento y fecha de la última valuación, con el botón **Actualizar valor**.
- Gráfico del valor en el tiempo (con las valuaciones cargadas) contra lo aportado.
- Historial de aportes y retiros de esa pestaña. En **Movimientos**, estos movimientos aparecen con la etiqueta "Inversión" y un filtro para ocultarlos.
- En **Resumen**, una línea debajo del disponible: "Ahorros e inversiones: $X · total con lo disponible: $Y".

## Bot

- Todo en lenguaje natural (aporte inicial, aporte, retiro, valuación). Botón o pregunta "¿cuánto tengo invertido?" sin IA.
- **Recordatorio semanal** (configurable, apagable): "¿Cuánto vale hoy Cocos? (última: $850.000 hace 7 días)", con el botón **Igual que antes**.
- **Reservas para metas:** "reservá 300.000 de Cocos para Bariloche" marca parte del valor como reservado para una meta (lo va a usar el pedido del viaje). Lo reservado se ve en la pestaña.

## Para más adelante (no lo hagas ahora)

Voy a armar una app propia de inversiones. Cuando exista, Gastito va a leer las valuaciones de ahí automáticamente. Dejá las valuaciones detrás de una interfaz (`FuenteDeValuaciones`) con la implementación manual, para poder sumar otra después.

## Tests

Saldo inicial que no cuenta como ingreso, aporte y retiro como entre cuentas (no afectan gastos ni ingresos), valuaciones y rendimiento (ganancia y pérdida), reserva para una meta, recordatorio con "Igual que antes", y el filtro en Movimientos.
