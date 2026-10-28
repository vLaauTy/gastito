-- Ícono (nombre de Lucide) con el que el dashboard muestra cada categoría. El emoji queda para el bot de Telegram.
ALTER TABLE categoria ADD COLUMN icono VARCHAR(40);

UPDATE categoria SET icono = 'store' WHERE nombre = 'Kiosco';
UPDATE categoria SET icono = 'shopping-cart' WHERE nombre = 'Supermercado';
UPDATE categoria SET icono = 'party-popper' WHERE nombre = 'Salidas';
UPDATE categoria SET icono = 'utensils' WHERE nombre = 'Comida afuera / delivery';
UPDATE categoria SET icono = 'house' WHERE nombre = 'Productos para la casa';
UPDATE categoria SET icono = 'zap' WHERE nombre = 'Servicios (luz, gas, servicios sociales)';
UPDATE categoria SET icono = 'bike' WHERE nombre = 'Transporte / Moto';
UPDATE categoria SET icono = 'repeat' WHERE nombre = 'Suscripciones';
UPDATE categoria SET icono = 'shield' WHERE nombre = 'Seguros';
UPDATE categoria SET icono = 'pill' WHERE nombre = 'Salud / farmacia';
UPDATE categoria SET icono = 'shirt' WHERE nombre = 'Ropa';
UPDATE categoria SET icono = 'receipt' WHERE nombre = 'Impuestos y cargos de tarjeta';
UPDATE categoria SET icono = 'circle-ellipsis' WHERE nombre = 'Otros';
