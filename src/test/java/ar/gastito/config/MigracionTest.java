package ar.gastito.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Corre las migraciones de Flyway sobre H2 en modo PostgreSQL y revisa el seed. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MigracionTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cargaLasCategoriasMediosYCuentas() {
        assertThat(contar("categoria")).isEqualTo(13);
        assertThat(contar("medio_pago")).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT nombre FROM cuenta ORDER BY id", String.class))
                .containsExactly("Galicia", "Mercado Pago");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categoria WHERE nombre = 'Otros'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void laTarjetaDebitaDeGalicia() {
        String cuenta = jdbc.queryForObject("""
                SELECT c.nombre FROM tarjeta t JOIN cuenta c ON c.id = t.cuenta_debito_id
                WHERE t.nombre = 'MasterCard Gold'""", String.class);
        assertThat(cuenta).isEqualTo("Galicia");
    }

    @Test
    void cargaElDiccionarioDeComercios() {
        String categoria = jdbc.queryForObject("""
                SELECT c.nombre FROM alias_comercio a JOIN categoria c ON c.id = a.categoria_id
                WHERE a.patron = 'FEDERA8'""", String.class);
        assertThat(categoria).isEqualTo("Seguros");
        assertThat(contar("alias_comercio")).isEqualTo(3);
    }

    @Test
    void rechazaMontosNoPositivos() {
        assertThatThrownBy(() -> insertarMovimiento("0", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validaLasCuotas() {
        insertarMovimiento("1500.50", 1, 3);
        assertThatThrownBy(() -> insertarMovimiento("1500", 4, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void todasLasCategoriasTienenEmojiYDescripcion() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM categoria WHERE emoji IS NULL OR descripcion IS NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT emoji FROM categoria WHERE nombre = 'Kiosco'", String.class))
                .isEqualTo("🍬");
    }

    @Test
    void unPendientePuedeNoTenerMontoNiCategoria() {
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, NULL, 'GASTO', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP)""");
    }

    @Test
    void unGastoConfirmadoNecesitaMontoYCategoria() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, categoria_id, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, NULL, 'GASTO', 1, 'TEXTO', 'CONFIRMADO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, categoria_id, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 100, 'GASTO', NULL, 'TEXTO', 'CONFIRMADO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardaCandidatasEnOrden() {
        jdbc.update("""
                INSERT INTO movimiento (id, fecha_hora, monto, tipo, origen, estado, creado_en)
                VALUES (1000, CURRENT_TIMESTAMP, 3700, 'GASTO', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP)""");
        jdbc.update("""
                INSERT INTO movimiento_categoria_candidata (movimiento_id, orden, categoria_id)
                SELECT 1000, 0, id FROM categoria WHERE nombre = 'Supermercado'""");
        jdbc.update("""
                INSERT INTO movimiento_categoria_candidata (movimiento_id, orden, categoria_id)
                SELECT 1000, 1, id FROM categoria WHERE nombre = 'Kiosco'""");

        assertThat(jdbc.queryForList("""
                SELECT c.nombre FROM movimiento_categoria_candidata mc JOIN categoria c ON c.id = mc.categoria_id
                WHERE mc.movimiento_id = 1000 ORDER BY mc.orden""", String.class))
                .containsExactly("Supermercado", "Kiosco");
    }

    // --- V3: ingresos y billeteras ---

    @Test
    void unIngresoConfirmadoNoNecesitaCategoriaPeroSiClase() {
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 380000, 'INGRESO', 'SUELDO', 'DASHBOARD', 'CONFIRMADO', CURRENT_TIMESTAMP)""");
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 190000, 'INGRESO', 'AGUINALDO', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP)""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 100, 'INGRESO', NULL, 'DASHBOARD', 'CONFIRMADO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 100, 'INGRESO', 'PREMIO', 'DASHBOARD', 'CONFIRMADO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unGastoNoLlevaClaseDeIngreso() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (CURRENT_TIMESTAMP, 100, 'GASTO', 'EXTRA', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void soloElEfectivoEsEfectivoYLasBilleterasTienenColor() {
        assertThat(jdbc.queryForList("SELECT nombre FROM medio_pago WHERE es_efectivo", String.class))
                .containsExactly("Efectivo");
        assertThat(jdbc.queryForObject("SELECT color FROM cuenta WHERE nombre = 'Galicia'", String.class))
                .isEqualTo("#1F7A4D");
        assertThat(jdbc.queryForObject("SELECT color FROM cuenta WHERE nombre = 'Mercado Pago'", String.class))
                .isEqualTo("#2457C5");
        assertThat(jdbc.queryForObject("SELECT color FROM tarjeta WHERE nombre = 'MasterCard Gold'", String.class))
                .isEqualTo("#B4531F");
    }

    // --- V4: tickets ---

    @Test
    void soloUnTicketTieneFoto() {
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, comercio, foto_ticket)
                VALUES (CURRENT_TIMESTAMP, 8050, 'GASTO', 'TICKET', 'PENDIENTE', CURRENT_TIMESTAMP, 'DIA',
                        'tickets/2026/10/1.jpg')""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, foto_ticket)
                VALUES (CURRENT_TIMESTAMP, 100, 'GASTO', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP, 'tickets/x.jpg')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void losProductosSeBorranConElMovimientoYNoRepitenOrden() {
        jdbc.update("""
                INSERT INTO movimiento (id, fecha_hora, monto, tipo, origen, estado, creado_en)
                VALUES (2000, CURRENT_TIMESTAMP, 8050, 'GASTO', 'TICKET', 'PENDIENTE', CURRENT_TIMESTAMP)""");
        jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, cantidad, precio_unitario, subtotal)
                VALUES (2000, 0, 'Leche 1 L', 2, 1450, 2900)""");
        jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, subtotal)
                VALUES (2000, 1, 'Descuento 2x1', -250)""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, subtotal)
                VALUES (2000, 1, 'Otro', 100)""")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, cantidad, subtotal)
                VALUES (2000, 2, 'Cero', 0, 100)""")).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM movimiento WHERE id = 2000");
        assertThat(contar("movimiento_item")).isZero();
    }

    // --- V5: lo que aprendimos de un ticket real ---

    @Test
    void elCodigoDeBarrasEsEan8OEan13YLosDescuentosImpresosNoSonPositivos() {
        jdbc.update("""
                INSERT INTO movimiento (id, fecha_hora, monto, tipo, origen, estado, creado_en, sucursal,
                                        subtotal_impreso, descuentos_impresos, lineas_ilegibles)
                VALUES (3000, CURRENT_TIMESTAMP, 30666.40, 'GASTO', 'TICKET', 'PENDIENTE', CURRENT_TIMESTAMP, '5601',
                        38333, -7666.60, 'Renglón 7: tapado')""");
        jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, descripcion_original, codigo_barras, subtotal)
                VALUES (3000, 0, 'Galletitas cracker queso', 'GALLET CRACKER QSO', '7792180145444', 4500)""");
        jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, codigo_barras, subtotal)
                VALUES (3000, 1, 'Chicle', '77912345', 100)""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento_item (movimiento_id, orden, descripcion, codigo_barras, subtotal)
                VALUES (3000, 2, 'Otro', '12345', 100)""")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, descuentos_impresos)
                VALUES (CURRENT_TIMESTAMP, 100, 'GASTO', 'TICKET', 'PENDIENTE', CURRENT_TIMESTAMP, 50)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- V6: resúmenes ---

    @Test
    void unPeriodoSoloSeRegistraUnaVezYUnEnviadoTieneSuResumen() {
        jdbc.update("""
                INSERT INTO resumen (id, tipo, desde, hasta, automatico, datos, creado_en)
                VALUES (1, 'SEMANAL', DATE '2026-10-12', DATE '2026-10-18', TRUE, '{}', CURRENT_TIMESTAMP)""");
        jdbc.update("""
                INSERT INTO resumen_envio (tipo, desde, hasta, estado, resumen_id, actualizado)
                VALUES ('SEMANAL', DATE '2026-10-12', DATE '2026-10-18', 'ENVIADO', 1, CURRENT_TIMESTAMP)""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO resumen_envio (tipo, desde, hasta, estado, actualizado)
                VALUES ('SEMANAL', DATE '2026-10-12', DATE '2026-10-18', 'OMITIDO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO resumen_envio (tipo, desde, hasta, estado, actualizado)
                VALUES ('MENSUAL', DATE '2026-10-01', DATE '2026-10-31', 'ENVIADO', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO resumen (tipo, desde, hasta, automatico, datos, creado_en)
                VALUES ('SEMANAL', DATE '2026-10-18', DATE '2026-10-12', FALSE, '{}', CURRENT_TIMESTAMP)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unReciboGuardaSusConceptosYSeBorraConEllos() {
        jdbc.update("""
                INSERT INTO movimiento (id, fecha_hora, monto, tipo, clase_ingreso, origen, estado, creado_en)
                VALUES (2000, CURRENT_TIMESTAMP, 1037500, 'INGRESO', 'SUELDO', 'RECIBO', 'PENDIENTE', CURRENT_TIMESTAMP)""");
        jdbc.update("""
                INSERT INTO recibo_sueldo (id, movimiento_id, empleador, periodo, tipo, bruto, descuentos, neto)
                VALUES (1, 2000, 'Empresa de Prueba', DATE '2026-10-01', 'SUELDO_MENSUAL', 1250000, 212500, 1037500)""");
        jdbc.update("""
                INSERT INTO recibo_concepto (recibo_id, descripcion, grupo, importe, orden)
                VALUES (1, 'Jubilación 11%', 'DESCUENTO', 137500, 0)""");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO recibo_sueldo (movimiento_id, empleador, periodo, tipo, bruto, descuentos, neto)
                VALUES (2000, 'Otra', DATE '2026-10-01', 'SUELDO_MENSUAL', 1, 1, 1)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO recibo_concepto (recibo_id, descripcion, grupo, importe, orden)
                VALUES (1, 'Algo', 'OTRO_GRUPO', 1, 1)"""))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM recibo_sueldo WHERE id = 1");
        assertThat(contar("recibo_concepto")).isZero();
    }

    @Test
    void elProposito_RECIBO_yElOrigen_RECIBO_sonValidos() {
        jdbc.update("""
                INSERT INTO uso_ia (fecha_hora, modelo, tokens_entrada, tokens_salida, proposito, costo_usd)
                VALUES (CURRENT_TIMESTAMP, 'claude-haiku-5-5', 3000, 900, 'RECIBO', 0.00075)""");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO uso_ia (fecha_hora, modelo, tokens_entrada, tokens_salida, proposito, costo_usd)
                VALUES (CURRENT_TIMESTAMP, 'claude-haiku-5-5', 1, 1, 'OTRO', 0)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elAvisoDelTopeSeGuardaUnaSolaVezPorMesYUmbral() {
        jdbc.update("INSERT INTO aviso_tope_ia (mes, umbral, enviado_en) VALUES (DATE '2026-10-01', 80, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO aviso_tope_ia (mes, umbral, enviado_en) VALUES (DATE '2026-10-01', 100, CURRENT_TIMESTAMP)");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO aviso_tope_ia (mes, umbral, enviado_en) VALUES (DATE '2026-10-01', 80, CURRENT_TIMESTAMP)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla, Integer.class);
    }

    private void insertarMovimiento(String monto, Integer cuotaActual, Integer cuotaTotal) {
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, cuota_actual, cuota_total)
                VALUES (CURRENT_TIMESTAMP, CAST(? AS NUMERIC(14, 2)), 'GASTO', 'TEXTO', 'PENDIENTE', CURRENT_TIMESTAMP, ?, ?)""",
                monto, cuotaActual, cuotaTotal);
    }
}
