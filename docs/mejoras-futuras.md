# Mejoras futuras

Ideas anotadas para más adelante. No son parte de ninguna etapa todavía.

## Categoría por producto en los tickets

Los tickets de supermercado mezclan rubros: en el ticket de Día del 22/09/2026 hay comida (galletitas,
premezcla, pan), higiene (pasta y cepillo dental, papel higiénico) y una bolsa. Hoy todo el ticket va a una sola
categoría (Supermercado).

Más adelante, cada renglón de `movimiento_item` podría tener su categoría, para ver cuánto se gasta de verdad en
higiene o limpieza aunque se compre en el súper. A tener en cuenta:

- El total del ticket sigue siendo un solo movimiento (un solo pago); las categorías por producto serían un
  desglose, y los descuentos se reparten entre los productos a los que se aplican.
- La IA puede proponer la categoría de cada producto en la misma lectura, y el historial (lo que el usuario ya
  corrigió) manda, igual que con los gastos. Los totales siguen saliendo de SQL.
- El código de barras (`movimiento_item.codigo_barras`) sirve para recordar la categoría de un producto que ya
  se compró, sin preguntarle a la IA.
