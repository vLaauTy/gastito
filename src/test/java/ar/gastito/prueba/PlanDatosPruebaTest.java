package ar.gastito.prueba;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Origen;

class PlanDatosPruebaTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 25, 20, 0);
    static final BigDecimal UMBRAL_HORMIGA = new BigDecimal("3000");

    private final PlanDatosPrueba.Plan plan = PlanDatosPrueba.armar(AHORA, 3, GeneradorDatosPrueba.SEMILLA);

    private Map<LocalDate, List<PlanDatosPrueba.Gasto>> porDia() {
        return plan.gastos().stream().collect(Collectors.groupingBy(g -> g.fechaHora().toLocalDate()));
    }

    private BigDecimal totalDe(YearMonth mes) {
        return plan.gastos().stream().filter(g -> YearMonth.from(g.fechaHora()).equals(mes))
                .map(PlanDatosPrueba.Gasto::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void tresMesesDesdeElUnoHastaAhoraSinNadaEnElFuturo() {
        assertThat(plan.desde()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(plan.hasta()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(plan.gastos()).allSatisfy(g -> assertThat(g.fechaHora()).isBeforeOrEqualTo(AHORA));
        assertThat(plan.gastos().get(0).fechaHora().toLocalDate()).isEqualTo(plan.desde());
    }

    @Test
    void deDosACincoGastosPorDia() {
        Map<LocalDate, List<PlanDatosPrueba.Gasto>> dias = porDia();

        for (LocalDate dia = plan.desde(); dia.isBefore(plan.hasta()); dia = dia.plusDays(1)) {
            assertThat(dias.get(dia)).as(dia.toString()).hasSizeBetween(2, 5);
        }
    }

    @Test
    void todasLasCategoriasYTodosLosMediosDePago() {
        assertThat(plan.gastos()).extracting(PlanDatosPrueba.Gasto::categoria).containsAll(List.of(
                PlanDatosPrueba.KIOSCO, PlanDatosPrueba.SUPERMERCADO, PlanDatosPrueba.SALIDAS, PlanDatosPrueba.COMIDA,
                PlanDatosPrueba.CASA, PlanDatosPrueba.SERVICIOS, PlanDatosPrueba.TRANSPORTE,
                PlanDatosPrueba.SUSCRIPCIONES, PlanDatosPrueba.SEGUROS, PlanDatosPrueba.SALUD, PlanDatosPrueba.ROPA,
                PlanDatosPrueba.IMPUESTOS, PlanDatosPrueba.OTROS));
        assertThat(plan.gastos()).extracting(PlanDatosPrueba.Gasto::medio).containsAll(List.of(
                PlanDatosPrueba.EFECTIVO, PlanDatosPrueba.DEBITO, PlanDatosPrueba.TARJETA,
                PlanDatosPrueba.TRANSFERENCIA, PlanDatosPrueba.QR));
        assertThat(plan.gastos()).extracting(PlanDatosPrueba.Gasto::origen)
                .contains(Origen.TEXTO, Origen.AUDIO, Origen.TICKET);
    }

    @Test
    void lasCuentasSalenDelMedioDePago() {
        assertThat(plan.gastos()).allSatisfy(g -> {
            switch (g.medio()) {
                case PlanDatosPrueba.DEBITO -> assertThat(g.cuenta()).isEqualTo(PlanDatosPrueba.GALICIA);
                case PlanDatosPrueba.QR -> assertThat(g.cuenta()).isEqualTo(PlanDatosPrueba.MERCADO_PAGO);
                case PlanDatosPrueba.TRANSFERENCIA -> assertThat(g.cuenta()).isNotNull();
                default -> assertThat(g.cuenta()).isNull();
            }
        });
    }

    @Test
    void gastosHormigaEnElKioscoCasiTodosLosDias() {
        long diasConHormiga = porDia().values().stream()
                .filter(gastos -> gastos.stream().anyMatch(g -> g.categoria().equals(PlanDatosPrueba.KIOSCO)
                        && g.monto().compareTo(UMBRAL_HORMIGA) < 0))
                .count();
        long dias = plan.desde().datesUntil(plan.hasta().plusDays(1)).count();

        assertThat(diasConHormiga).isGreaterThanOrEqualTo(dias * 3 / 4);
    }

    @Test
    void losGastosFijosVanTodosLosMeses() {
        for (YearMonth mes = YearMonth.of(2026, 8); !mes.isAfter(YearMonth.of(2026, 10)); mes = mes.plusMonths(1)) {
            YearMonth m = mes;
            assertThat(plan.gastos()).filteredOn(g -> YearMonth.from(g.fechaHora()).equals(m))
                    .extracting(PlanDatosPrueba.Gasto::descripcion)
                    .contains("Internet", "Luz", "Netflix", "FEDERA8 seguro de la moto", "Impuesto de sellos");
        }
    }

    @Test
    void losTicketsDelDiaCierranComoUnTicketReal() {
        List<PlanDatosPrueba.Gasto> tickets = plan.gastos().stream().filter(g -> g.ticket() != null).toList();

        assertThat(tickets).hasSizeGreaterThanOrEqualTo(5);
        assertThat(tickets).allSatisfy(g -> {
            PlanDatosPrueba.Ticket t = g.ticket();
            BigDecimal suma = t.items().stream().map(ItemTicket::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(g.categoria()).isEqualTo(PlanDatosPrueba.SUPERMERCADO);
            assertThat(g.origen()).isEqualTo(Origen.TICKET);
            assertThat(suma).isEqualByComparingTo(g.monto());
            assertThat(t.subtotal().add(t.descuentos())).isEqualByComparingTo(g.monto());
            // Cada descuento va debajo de un producto, y con subtotal negativo.
            for (int i = 0; i < t.items().size(); i++) {
                if (t.items().get(i).subtotal().signum() < 0) {
                    assertThat(i).isPositive();
                    assertThat(t.items().get(i - 1).subtotal().signum()).isPositive();
                }
            }
        });
    }

    @Test
    void elMesDelMedioGastaBastanteMas() {
        assertThat(plan.mesCaro()).isEqualTo(YearMonth.of(2026, 9));
        BigDecimal agosto = totalDe(YearMonth.of(2026, 8));
        BigDecimal septiembre = totalDe(YearMonth.of(2026, 9));

        assertThat(septiembre).isGreaterThan(agosto.multiply(new BigDecimal("1.2")));
    }

    @Test
    void sueldoTodosLosMesesUnAguinaldoYExtras() {
        assertThat(plan.ingresos()).filteredOn(i -> i.clase() == ClaseIngreso.SUELDO)
                .extracting(i -> YearMonth.from(i.fecha()))
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(plan.ingresos()).filteredOn(i -> i.clase() == ClaseIngreso.AGUINALDO).hasSize(1);
        assertThat(plan.ingresos()).filteredOn(i -> i.clase() == ClaseIngreso.EXTRA).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(i -> assertThat(i.descripcion()).isNotBlank());
        // En un mes normal el sueldo alcanza; el caro se come casi todo.
        BigDecimal sueldoAgosto = plan.ingresos().get(0).monto();
        assertThat(totalDe(YearMonth.of(2026, 8))).isLessThan(sueldoAgosto);
    }

    @Test
    void conLaMismaSemillaSaleLoMismo() {
        assertThat(PlanDatosPrueba.armar(AHORA, 3, GeneradorDatosPrueba.SEMILLA)).isEqualTo(plan);
        assertThat(PlanDatosPrueba.armar(AHORA, 3, 1L)).isNotEqualTo(plan);
    }

    @Test
    void elGeneradorSoloTocaLaBaseDePrueba() {
        GeneradorDatosPrueba.comprobarBase("jdbc:postgresql://localhost:5432/gastito_prueba", false);
        GeneradorDatosPrueba.comprobarBase("jdbc:postgresql://localhost:5432/gastito_prueba?ssl=false", false);

        assertThatThrownBy(() -> GeneradorDatosPrueba.comprobarBase("jdbc:postgresql://localhost:5432/gastito",
                false)).hasMessageContaining("gastito_prueba");
        assertThatThrownBy(() -> GeneradorDatosPrueba.comprobarBase(
                "jdbc:postgresql://localhost:5432/gastito_prueba", true)).hasMessageContaining("postgres");
        assertThatThrownBy(() -> GeneradorDatosPrueba.comprobarBase(null, false))
                .isInstanceOf(IllegalStateException.class);
    }
}
