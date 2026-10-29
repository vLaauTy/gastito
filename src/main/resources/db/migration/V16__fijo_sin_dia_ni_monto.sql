-- Fijos dados de alta por mensaje o audio sin todos los datos ("No sé" el día o el monto).
-- Sin día: aparece en el checklist del mes pero sin recordatorio de vencimiento ni cobro automático.
-- Sin monto: no hay monto de referencia hasta el primer pago.
ALTER TABLE gasto_fijo ALTER COLUMN dia_del_mes DROP NOT NULL;
ALTER TABLE gasto_fijo ALTER COLUMN monto_esperado DROP NOT NULL;
