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
