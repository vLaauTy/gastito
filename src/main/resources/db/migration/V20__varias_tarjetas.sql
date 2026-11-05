-- Más de una tarjeta de crédito: cada compra con crédito sabe de qué tarjeta es y cada tarjeta tiene su ciclo.

-- Las palabras con las que se la nombra ("master", "la de mercado pago"), sin tildes y separadas por coma; las que
-- también nombran una cuenta ("galicia", "mercado pago") solas son dudosas: pueden ser plata en esa cuenta.
ALTER TABLE tarjeta ADD COLUMN palabras_clave VARCHAR(500);
-- La letra que la representa en la web (con el color).
ALTER TABLE tarjeta ADD COLUMN inicial VARCHAR(2);
-- La pregunta por las fechas de una tarjeta que todavía no tiene ningún cierre (no hay resumen donde anotarla).
ALTER TABLE tarjeta ADD COLUMN pregunta_fechas_en DATE;
ALTER TABLE tarjeta ADD COLUMN fechas_pospuestas_hasta DATE;

UPDATE tarjeta SET palabras_clave = 'master,mastercard,gold,la de galicia,galicia', inicial = 'M'
    WHERE nombre = 'MasterCard Gold';

INSERT INTO tarjeta (nombre, banco, cuenta_debito_id, activa, color, palabras_clave, inicial)
    SELECT 'Mercado Pago', 'Mercado Pago', id, TRUE, '#2D6CDF',
        'la de mercado pago,tarjeta de mercado pago,mercado pago,mp', 'P'
    FROM cuenta WHERE nombre = 'Mercado Pago'
      AND NOT EXISTS (SELECT 1 FROM tarjeta WHERE nombre = 'Mercado Pago');

-- Lo que ya existe con crédito y sin tarjeta queda en la MasterCard Gold (hasta ahora era la única).
UPDATE movimiento SET tarjeta_id = (SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold')
    WHERE tarjeta_id IS NULL
      AND medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre = 'Tarjeta de crédito');
UPDATE gasto_fijo SET tarjeta_id = (SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold')
    WHERE tarjeta_id IS NULL
      AND medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre = 'Tarjeta de crédito');
UPDATE juntada SET tarjeta_id = (SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold')
    WHERE tarjeta_id IS NULL
      AND medio_pago_id IN (SELECT id FROM medio_pago WHERE nombre = 'Tarjeta de crédito');
UPDATE plan_cuotas SET tarjeta_id = (SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold')
    WHERE tarjeta_id IS NULL;
