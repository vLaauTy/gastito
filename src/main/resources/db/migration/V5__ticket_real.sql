-- Lo que aprendimos de un ticket real (Día, 22/09/2026). Tiene que correr igual en H2 y en PostgreSQL.

-- De cada producto: la descripción como está impresa ("GALLET CRACKER QSO") además de la legible, y el código
-- de barras (EAN-8 o EAN-13, validado en Java con su dígito verificador). Un código de barras identifica un
-- producto, no a una persona.
ALTER TABLE movimiento_item ADD COLUMN descripcion_original VARCHAR(255);
ALTER TABLE movimiento_item ADD COLUMN codigo_barras VARCHAR(13);
ALTER TABLE movimiento_item ADD CONSTRAINT ck_movimiento_item_codigo_barras
    CHECK (codigo_barras IS NULL OR LENGTH(codigo_barras) IN (8, 13));

-- Del ticket: la sucursal ("5601", para cruzarlo con DIA TIENDA 5601 del resumen de la tarjeta en la etapa 7),
-- el subtotal y los descuentos impresos (para los controles: productos = subtotal, descuentos = descuentos y
-- subtotal + descuentos = total) y las líneas que la IA no pudo leer (ticket arrugado o tapado).
ALTER TABLE movimiento ADD COLUMN sucursal VARCHAR(20);
ALTER TABLE movimiento ADD COLUMN subtotal_impreso NUMERIC(14, 2);
ALTER TABLE movimiento ADD COLUMN descuentos_impresos NUMERIC(14, 2);
ALTER TABLE movimiento ADD COLUMN lineas_ilegibles VARCHAR(1000);
ALTER TABLE movimiento ADD CONSTRAINT ck_movimiento_descuentos_impresos
    CHECK (descuentos_impresos IS NULL OR descuentos_impresos <= 0);
