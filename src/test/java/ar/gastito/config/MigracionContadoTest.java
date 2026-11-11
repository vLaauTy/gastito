package ar.gastito.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La V23 sobre datos que ya existían: lo pagado con efectivo, débito, transferencia o QR pasa a Contado y conserva
 * cómo se pagó en detalle_pago; lo de crédito no cambia, y los medios viejos dejan de ofrecerse.
 */
class MigracionContadoTest {

    private final DriverManagerDataSource base = new DriverManagerDataSource(
            "jdbc:h2:mem:contado;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(base);

    private Flyway flyway(String hasta) {
        return Flyway.configure().dataSource(base).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(hasta)).load();
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long medio(String nombre) {
        return id("SELECT id FROM medio_pago WHERE nombre = '" + nombre + "'");
    }

    @Test
    void loDeContadoPasaAContadoConSuDetalle() {
        flyway("22").migrate();
        long kiosco = id("SELECT id FROM categoria WHERE nombre = 'Kiosco'");
        long galicia = id("SELECT id FROM cuenta WHERE nombre = 'Galicia'");
        long efectivo = medio("Efectivo");
        String gasto = """
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, categoria_id, medio_pago_id,
                    cuenta_id, tarjeta_id, descripcion)
                VALUES (CURRENT_TIMESTAMP, 1000, 'GASTO', 'TEXTO', 'CONFIRMADO', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)""";
        jdbc.update(gasto, kiosco, efectivo, null, null, "efectivo");
        jdbc.update(gasto, kiosco, medio("Débito"), null, null, "débito");
        jdbc.update(gasto, kiosco, medio("QR"), galicia, null, "qr");
        jdbc.update(gasto, kiosco, medio("Transferencia"), null, null, "transferencia");
        jdbc.update(gasto, kiosco, null, galicia, null, "solo cuenta");
        jdbc.update(gasto, kiosco, null, null, null, "sin medio");
        long gold = id("SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold'");
        jdbc.update(gasto, kiosco, medio("Tarjeta de crédito"), null, gold, "crédito");
        jdbc.update("""
                INSERT INTO gasto_fijo (nombre, monto_esperado, categoria_id, medio_pago_id, dia_del_mes, frecuencia,
                    mes_inicio, creado_en)
                VALUES ('Luz', 30000, ?, ?, 7, 'MENSUAL', DATE '2026-10-01', CURRENT_TIMESTAMP)""", kiosco,
                medio("Débito"));

        flyway("23").migrate();

        long contado = medio("Contado");
        assertThat(contado).isEqualTo(efectivo);
        List<String> filas = jdbc.query("""
                SELECT m.descripcion, mp.nombre, m.detalle_pago FROM movimiento m
                LEFT JOIN medio_pago mp ON mp.id = m.medio_pago_id ORDER BY m.id""",
                (rs, i) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        assertThat(filas).containsExactly(
                "efectivo|Contado|Efectivo",
                "débito|Contado|Débito",
                "qr|Contado|QR · Galicia",
                "transferencia|Contado|Transferencia",
                "solo cuenta|Contado|Galicia",
                "sin medio|Contado|null",
                "crédito|Tarjeta de crédito|null");
        assertThat(id("SELECT medio_pago_id FROM gasto_fijo WHERE nombre = 'Luz'")).isEqualTo(contado);
        assertThat(jdbc.queryForList("SELECT nombre FROM medio_pago WHERE activo ORDER BY id", String.class))
                .containsExactly("Contado", "Tarjeta de crédito");
    }
}
