package ar.gastito.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La migración a varias tarjetas sobre datos que ya existían: una base en la V19 con gastos, un fijo, un plan de
 * cuotas y una juntada con crédito y sin tarjeta; después de la V20 quedan todos en la MasterCard Gold, y los demás
 * medios siguen sin tarjeta.
 */
class MigracionVariasTarjetasTest {

    private final DriverManagerDataSource base = new DriverManagerDataSource(
            "jdbc:h2:mem:varias-tarjetas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                    + "DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(base);

    private Flyway flyway(String hasta) {
        return Flyway.configure().dataSource(base).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(hasta)).load();
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void loQueYaHabiaConCreditoQuedaEnLaMasterCardGold() {
        flyway("19").migrate();
        long credito = id("SELECT id FROM medio_pago WHERE nombre = 'Tarjeta de crédito'");
        long debito = id("SELECT id FROM medio_pago WHERE nombre = 'Débito'");
        long ropa = id("SELECT id FROM categoria WHERE nombre = 'Ropa'");
        String gasto = """
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, categoria_id, medio_pago_id,
                    descripcion)
                VALUES (CURRENT_TIMESTAMP, 1000, 'GASTO', 'TEXTO', 'CONFIRMADO', CURRENT_TIMESTAMP, ?, ?, ?)""";
        jdbc.update(gasto, ropa, credito, "con crédito");
        jdbc.update(gasto, ropa, debito, "con débito");
        jdbc.update("""
                INSERT INTO gasto_fijo (nombre, monto_esperado, categoria_id, medio_pago_id, dia_del_mes, frecuencia,
                    mes_inicio, creado_en)
                VALUES ('Netflix', 11999, ?, ?, 7, 'MENSUAL', DATE '2026-10-01', CURRENT_TIMESTAMP)""", ropa, credito);
        jdbc.update("""
                INSERT INTO plan_cuotas (descripcion, total, cantidad, valor_cuota, fecha_compra, primer_mes,
                    categoria_id, estado, creado_en)
                VALUES ('Zapatillas', 30000, 3, 10000, CURRENT_TIMESTAMP, DATE '2026-10-01', ?, 'ACTIVO',
                    CURRENT_TIMESTAMP)""", ropa);
        long conCredito = id("SELECT id FROM movimiento WHERE descripcion = 'con crédito'");
        jdbc.update("""
                INSERT INTO juntada (movimiento_id, total_pagado, personas, medio_pago_id, creado_en)
                VALUES (?, 3000, 3, ?, CURRENT_TIMESTAMP)""", conCredito, credito);

        flyway("20").migrate();

        long gold = id("SELECT id FROM tarjeta WHERE nombre = 'MasterCard Gold'");
        assertThat(id("SELECT tarjeta_id FROM movimiento WHERE descripcion = 'con crédito'")).isEqualTo(gold);
        assertThat(jdbc.queryForObject("SELECT tarjeta_id FROM movimiento WHERE descripcion = 'con débito'",
                Long.class)).isNull();
        assertThat(id("SELECT tarjeta_id FROM gasto_fijo WHERE nombre = 'Netflix'")).isEqualTo(gold);
        assertThat(id("SELECT tarjeta_id FROM plan_cuotas WHERE descripcion = 'Zapatillas'")).isEqualTo(gold);
        assertThat(id("SELECT tarjeta_id FROM juntada")).isEqualTo(gold);
        assertThat(jdbc.queryForObject("""
                SELECT c.nombre FROM tarjeta t JOIN cuenta c ON c.id = t.cuenta_debito_id
                WHERE t.nombre = 'Mercado Pago'""", String.class)).isEqualTo("Mercado Pago");
        assertThat(jdbc.queryForObject("SELECT palabras_clave FROM tarjeta WHERE nombre = 'MasterCard Gold'",
                String.class)).contains("master", "la de galicia");
    }
}
