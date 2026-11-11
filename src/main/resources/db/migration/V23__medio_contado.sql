-- Medio de pago simplificado: Contado (efectivo, débito, transferencia, QR: es lo mismo) y Tarjeta de crédito.
-- Lo que era efectivo, débito, transferencia o QR pasa a Contado y conserva cómo se pagó en detalle_pago.

-- Cómo se pagó de contado, opcional: "Débito", "Transferencia", "Efectivo", "QR", "Mercado Pago"…
ALTER TABLE movimiento ADD COLUMN detalle_pago VARCHAR(40);

-- El detalle de lo que ya existe: el medio viejo y, si tenía, la cuenta ("QR · Mercado Pago").
UPDATE movimiento SET detalle_pago =
        (SELECT mp.nombre FROM medio_pago mp WHERE mp.id = movimiento.medio_pago_id)
        || COALESCE(' · ' || (SELECT c.nombre FROM cuenta c WHERE c.id = movimiento.cuenta_id), '')
    WHERE tipo = 'GASTO'
      AND medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre IN ('Efectivo', 'Débito', 'Transferencia', 'QR'));
-- Un gasto sin medio pero con cuenta ("desde Mercado Pago"): la cuenta es el detalle.
UPDATE movimiento SET detalle_pago = (SELECT c.nombre FROM cuenta c WHERE c.id = movimiento.cuenta_id)
    WHERE tipo = 'GASTO' AND medio_pago_id IS NULL AND tarjeta_id IS NULL AND cuenta_id IS NOT NULL;

-- Efectivo pasa a ser Contado (mismo id; ya es la billetera "en efectivo" de los ingresos y los pases).
UPDATE medio_pago SET nombre = 'Contado',
        palabras_clave = 'contado,efectivo,cash,billete,billetes,debito,transferencia,transferi,qr'
    WHERE nombre = 'Efectivo';

-- Todo lo que usaba Débito, Transferencia o QR pasa a Contado; un gasto sin medio ni tarjeta, también.
UPDATE movimiento SET medio_pago_id = (SELECT id FROM medio_pago WHERE nombre = 'Contado')
    WHERE medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre IN ('Débito', 'Transferencia', 'QR'))
       OR (tipo = 'GASTO' AND medio_pago_id IS NULL AND tarjeta_id IS NULL);
UPDATE gasto_fijo SET medio_pago_id = (SELECT id FROM medio_pago WHERE nombre = 'Contado')
    WHERE medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre IN ('Débito', 'Transferencia', 'QR'));
UPDATE juntada SET medio_pago_id = (SELECT id FROM medio_pago WHERE nombre = 'Contado')
    WHERE medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre IN ('Débito', 'Transferencia', 'QR'));
UPDATE prestamo SET medio_pago_id = (SELECT id FROM medio_pago WHERE nombre = 'Contado')
    WHERE medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre IN ('Débito', 'Transferencia', 'QR'));

-- Los medios viejos no se borran (por si algo los nombra): dejan de aparecer para elegir.
UPDATE medio_pago SET activo = FALSE WHERE nombre IN ('Débito', 'Transferencia', 'QR');
