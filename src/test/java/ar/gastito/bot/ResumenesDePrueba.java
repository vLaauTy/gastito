package ar.gastito.bot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import ar.gastito.resumen.DatosResumen;
import ar.gastito.resumen.PeriodoResumen;
import ar.gastito.resumen.Resumen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

/** Un resumen armado sin gastos, para los tests del bot. */
final class ResumenesDePrueba {

    private ResumenesDePrueba() {
    }

    static ResumenService.Armado vacio(TipoResumen tipo, LocalDate dia) {
        PeriodoResumen periodo = PeriodoResumen.de(tipo, dia);
        Resumen r = new Resumen(tipo, periodo.desde(), periodo.hasta(), false, false, "{}", List.of("Bien."), null,
                false, null, LocalDateTime.of(2026, 10, 13, 16, 42));
        BigDecimal cero = BigDecimal.ZERO.setScale(2);
        DatosResumen d = new DatosResumen(new DatosResumen.Periodo(tipo.name(), periodo.desde(), periodo.hasta(),
                periodo.hasta(), false, (int) periodo.diasHasta(periodo.hasta())), cero, 0,
                new DatosResumen.Comparacion(periodo.anterior().desde(), periodo.anterior().hasta(), cero, null),
                List.of(), null, new DatosResumen.Hormiga(new BigDecimal("3000.00"), List.of(), 0, cero, cero,
                        BigDecimal.ZERO), 7, new DatosResumen.SinEspecificar(0, cero),
                new DatosResumen.Productos(List.of(), List.of()), List.of(), null, null, false, List.of());
        return new ResumenService.Armado(r, d);
    }
}
