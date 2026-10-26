-- Juntadas: la parte de alguien que se cambió a mano ("Martín puso 5000") no se recalcula al cambiar las demás.
ALTER TABLE juntada_participante ADD COLUMN monto_a_mano BOOLEAN NOT NULL DEFAULT FALSE;
