-- Categorías con descripción y emoji, candidatas para los gastos dudosos,
-- y pendientes sin monto (se completa editando la tarjeta).
-- Tiene que correr igual en H2 (modo PostgreSQL) y en PostgreSQL.

-- ---------------------------------------------------------------------------
-- Descripción (se le pasa a la IA) y emoji (se usa en la tarjeta y los resúmenes)
-- ---------------------------------------------------------------------------

ALTER TABLE categoria ADD COLUMN descripcion VARCHAR(500);
ALTER TABLE categoria ADD COLUMN emoji VARCHAR(16);

UPDATE categoria SET emoji = '🍬',
    descripcion = 'Golosinas, bebidas, cigarrillos, snacks, compras chicas al paso.'
    WHERE nombre = 'Kiosco';
UPDATE categoria SET emoji = '🛒',
    descripcion = 'Comida y productos de almacén para la casa (pan, fiambre, verdura, carne), supermercados, almacenes, autoservicios.'
    WHERE nombre = 'Supermercado';
UPDATE categoria SET emoji = '🍻',
    descripcion = 'Bares, boliches, cine, eventos, entradas, salidas con amigos fuera de casa.'
    WHERE nombre = 'Salidas';
UPDATE categoria SET emoji = '🍔',
    descripcion = 'Comida lista en restaurantes, rotiserías o pedida por app.'
    WHERE nombre = 'Comida afuera / delivery';
UPDATE categoria SET emoji = '🧽',
    descripcion = 'Limpieza, artículos del hogar, ferretería.'
    WHERE nombre = 'Productos para la casa';
UPDATE categoria SET emoji = '💡',
    descripcion = 'Luz, gas, agua, internet, celular, servicios sociales.'
    WHERE nombre = 'Servicios (luz, gas, servicios sociales)';
UPDATE categoria SET emoji = '🏍️',
    descripcion = 'Nafta, colectivo, SUBE, Uber, repuestos y service de la moto, peajes.'
    WHERE nombre = 'Transporte / Moto';
UPDATE categoria SET emoji = '📺',
    descripcion = 'Streaming, apps, servicios digitales mensuales.'
    WHERE nombre = 'Suscripciones';
UPDATE categoria SET emoji = '🛡️',
    descripcion = 'Seguro de la moto y otros seguros.'
    WHERE nombre = 'Seguros';
UPDATE categoria SET emoji = '💊',
    descripcion = 'Farmacia, médicos, análisis.'
    WHERE nombre = 'Salud / farmacia';
UPDATE categoria SET emoji = '👕',
    descripcion = 'Ropa y calzado.'
    WHERE nombre = 'Ropa';
UPDATE categoria SET emoji = '🧾',
    descripcion = 'Impuesto de sellos, IVA, percepciones, intereses, comisiones.'
    WHERE nombre = 'Impuestos y cargos de tarjeta';
UPDATE categoria SET emoji = '📦',
    descripcion = 'Lo que no encaja en ninguna otra categoría.'
    WHERE nombre = 'Otros';

-- ---------------------------------------------------------------------------
-- Pendientes sin monto ni categoría: se completan desde la tarjeta antes de guardar
-- ---------------------------------------------------------------------------

ALTER TABLE movimiento ALTER COLUMN monto DROP NOT NULL;
ALTER TABLE movimiento DROP CONSTRAINT ck_movimiento_monto;
ALTER TABLE movimiento ADD CONSTRAINT ck_movimiento_monto
    -- Explícito con IS NOT NULL: "NULL > 0" da NULL y un CHECK con NULL no falla.
    CHECK ((monto IS NOT NULL AND monto > 0) OR (monto IS NULL AND estado <> 'CONFIRMADO'));
-- Un gasto confirmado siempre tiene categoría (los ingresos de etapas futuras pueden no tenerla).
ALTER TABLE movimiento ADD CONSTRAINT ck_movimiento_gasto_con_categoria
    CHECK (categoria_id IS NOT NULL OR estado <> 'CONFIRMADO' OR tipo <> 'GASTO');

-- Hasta 3 categorías posibles, en orden, para un gasto dudoso que todavía no tiene categoría elegida.
CREATE TABLE movimiento_categoria_candidata (
    movimiento_id BIGINT  NOT NULL REFERENCES movimiento (id) ON DELETE CASCADE,
    orden         INTEGER NOT NULL,
    categoria_id  BIGINT  NOT NULL REFERENCES categoria (id),
    PRIMARY KEY (movimiento_id, orden)
);
