# Pedido: arreglos de diseño del dashboard

Leé `CLAUDE.md`. Trabajá nativo, solo HTML/CSS/JS del dashboard, commits chicos, push al final y reporte de 10 líneas como máximo.

## Errores encontrados

1. **Espacio en blanco en tarjetas con tablas (PC).** `.table-card { flex: 999 1 520px }` está pensado para una fila (tabla + columna lateral), pero en las vistas donde `.view` es una columna, el `flex-basis` de 520px se aplica a la **altura**: cada tabla mide como mínimo 520px aunque tenga una sola fila. Pasa en **Fijos** (Checklist del mes y Tus gastos fijos) y en **Costos IA** (Costo por propósito y Las llamadas más caras). Arreglalo para que la tarjeta mida lo que mide su contenido: el `flex` de `.table-card` solo dentro de `.row2` (o del contenedor en fila), no como regla general.
2. **Textos viejos de "Próximamente":**
   - En Resumen, la tarjeta "Fijos y juntadas · Próximamente: llegan en la etapa 6". La etapa 6 está hecha: reemplazala por una tarjeta real con lo que falta cargar de fijos este mes y la plata a cobrar de juntadas (con link a cada sección), o sacala.
   - En el menú, "Cuotas y juntadas · Próximamente". Si las secciones de Juntadas y Cuotas que pedía la Parte F de la etapa 6 no están hechas, decímelo y hacelas (juntadas abiertas con quién debe cuánto y cerradas; cuotas pendientes y lo comprometido en los próximos 6 meses). Si ya existen en otra sección, sacá el item del menú.
3. **Alineación vertical** en las celdas con ícono (por ejemplo, el nombre del fijo con su ícono queda más arriba que el resto de la fila). Todas las celdas centradas verticalmente.
4. **Tarjetas de números estiradas.** En Fijos, "Cargado este mes" y "Falta cargar" ocupan cada una media pantalla para un solo número. Que las tarjetas de números tengan un ancho máximo razonable (o sumá una tercera útil, como el próximo vencimiento).

## Revisión de errores parecidos

Revisá **todas** las vistas (Resumen, Movimientos, Ingresos con calculadora, Fijos, Costos IA, Categorías, y los formularios y diálogos) con el navegador headless, a 1366 × 768 y a 390 × 844, en modo claro y oscuro, en **tres situaciones de datos**:

- **Vacío:** base sin movimientos (estados vacíos que se vean bien, sin tarjetas gigantes ni tablas con solo el encabezado).
- **Poco:** un solo elemento en cada lista (el caso que encontré).
- **Mucho:** los datos de prueba completos (textos largos que no rompan, tablas que no desborden).

Buscá en especial: tarjetas con altura forzada o espacio vacío, elementos que se estiran sin contenido, textos cortados o que se salen, scroll horizontal en el celular, columnas desalineadas, textos de etapas pasadas ("Próximamente", "etapa N") y botones que no hacen nada.

Arreglá lo que encuentres y mandame capturas antes/después de lo más importante.
