-- Fijos tildados con el monto aproximado ("Pagado"): el gasto queda marcado hasta que se corrige con el real.
ALTER TABLE movimiento ADD COLUMN monto_estimado BOOLEAN NOT NULL DEFAULT FALSE;
