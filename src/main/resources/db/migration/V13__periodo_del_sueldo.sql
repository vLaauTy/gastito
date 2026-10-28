-- El mes trabajado de un sueldo o aguinaldo (siempre el día 1). El sueldo se cobra alrededor del día 5 del mes
-- siguiente: la fecha del ingreso dice cuándo entró la plata (cuenta para el disponible de ese mes) y el período,
-- a qué mes pertenece (con él se vinculan el cálculo y el recibo). Vacío en los demás ingresos.
ALTER TABLE movimiento ADD COLUMN periodo DATE;
