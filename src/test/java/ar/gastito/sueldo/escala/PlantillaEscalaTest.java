package ar.gastito.sueldo.escala;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class PlantillaEscalaTest {

    @Test
    void laPlantillaTieneElFormatoDeLaHojaYSeVuelveALeerIgual() {
        EscalaDePrueba.Repos repos = EscalaDePrueba.repos();
        PlantillaEscala plantilla = new PlantillaEscala(repos.conceptos(), repos.escalas(), repos.alias());

        HojaEscala hoja = new LectorExcelEscala().leer(plantilla.generar(YearMonth.of(2026, 10)));

        assertThat(hoja.columnas()).singleElement().satisfies(c -> {
            assertThat(c.mes()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(c.filas()).extracting(HojaEscala.Fila::nombre).containsExactly("HORAS NORMALES", "BASICO",
                    "PRESENTISMO", "A.I.S.", "VIATICOS", "ASIG. EXT. NO REMUNERATIVA 6%", "VALORES NETOS");
        });
        // Los valores de la escala vigente (agosto) y "Valores netos" calculado con sus descuentos: el control cierra.
        ImportacionEscala.MesPropuesto m = repos.importacion().proponer(hoja).meses().getFirst();
        assertThat(m.valores().get(EscalaDePrueba.BASICO.getId())).isEqualByComparingTo("460000");
        assertThat(m.valorHora()).isEqualByComparingTo("3000");
        assertThat(m.control().hoja()).isEqualByComparingTo("742400");
        assertThat(m.control().ok()).isTrue();
    }
}
