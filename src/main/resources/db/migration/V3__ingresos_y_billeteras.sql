-- Ingresos (adelantados de la etapa 6), carga desde el dashboard y datos para agrupar los gastos por billetera.
-- Tiene que correr igual en H2 (modo PostgreSQL) y en PostgreSQL.

-- ---------------------------------------------------------------------------
-- Clase de ingreso: el aguinaldo va aparte para no inflar la comparación del sueldo entre meses.
-- ---------------------------------------------------------------------------

ALTER TABLE movimiento ADD COLUMN clase_ingreso VARCHAR(20);
ALTER TABLE movimiento ADD CONSTRAINT ck_movimiento_clase_ingreso CHECK (
    -- Explícito con IS NOT NULL: "NULL IN (...)" da NULL y un CHECK con NULL no falla.
    (tipo = 'INGRESO' AND clase_ingreso IS NOT NULL AND clase_ingreso IN ('SUELDO', 'AGUINALDO', 'EXTRA'))
    OR (tipo <> 'INGRESO' AND clase_ingreso IS NULL));

-- Los ingresos cargados en el formulario del dashboard.
ALTER TABLE movimiento DROP CONSTRAINT ck_movimiento_origen;
ALTER TABLE movimiento ADD CONSTRAINT ck_movimiento_origen
    CHECK (origen IN ('TEXTO', 'AUDIO', 'TICKET', 'AVISO', 'RESUMEN', 'DASHBOARD'));

-- ---------------------------------------------------------------------------
-- Billeteras: un gasto se agrupa por su tarjeta, su cuenta o, si se pagó en efectivo, como efectivo.
-- ---------------------------------------------------------------------------

ALTER TABLE medio_pago ADD COLUMN es_efectivo BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE medio_pago SET es_efectivo = TRUE WHERE nombre = 'Efectivo';

-- Color de la billetera en el dashboard (#RRGGBB). Sin color, el dashboard usa uno de reserva.
ALTER TABLE cuenta ADD COLUMN color VARCHAR(7);
ALTER TABLE tarjeta ADD COLUMN color VARCHAR(7);
UPDATE cuenta SET color = '#1F7A4D' WHERE nombre = 'Galicia';
UPDATE cuenta SET color = '#2457C5' WHERE nombre = 'Mercado Pago';
UPDATE tarjeta SET color = '#B4531F' WHERE nombre = 'MasterCard Gold';
