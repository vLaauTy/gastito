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
