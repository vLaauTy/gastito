package ar.gastito.sueldo.escala;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.ConceptoSueldoRepository;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.EscalaSueldoRepository;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * Conceptos, alias y escalas como los de la migración, con valores inventados (no son los de la hoja real). La hoja
 * inventada cumple "Valores netos = (básico + presentismo + A.I.S.) × 0,80 + asignación 6% + viáticos".
 */
final class EscalaDePrueba {

    static final ConceptoSueldo BASICO = concepto(1, "Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo PRESENTISMO = concepto(2, "Presentismo", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo CCT = concepto(3, "CCT planta nuclear", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo AIS = concepto(4, "Asignación integración social", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo AL_50 = concepto(5, "Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2);
    static final ConceptoSueldo AL_100 = concepto(6, "Horas al 100%", TipoConceptoSueldo.HORAS, 2, 1);
    static final ConceptoSueldo NOCTURNAS = concepto(7, "Horas nocturnas", TipoConceptoSueldo.HORAS, 8, 60);
    static final ConceptoSueldo VIATICO = concepto(8, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo EXTRAORDINARIA = concepto(9, "Asignación extraordinaria", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo JUBILACION = concepto(10, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo OBRA_SOCIAL = concepto(11, "Obra social", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo LEY = concepto(12, "Ley 19.032", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo SINDICATO = concepto(13, "Sindicato", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);

    static final List<ConceptoSueldo> CONCEPTOS = List.of(BASICO, PRESENTISMO, CCT, AIS, AL_50, AL_100, NOCTURNAS,
            VIATICO, EXTRAORDINARIA, JUBILACION, OBRA_SOCIAL, LEY, SINDICATO);

    static final List<AliasEscala> ALIAS = List.of(
            new AliasEscala("BASICO", DestinoAlias.CONCEPTO, BASICO.getId()),
            new AliasEscala("PRESENTISMO", DestinoAlias.CONCEPTO, PRESENTISMO.getId()),
            new AliasEscala("A.I.S.", DestinoAlias.CONCEPTO, AIS.getId()),
            new AliasEscala("ASIG. EXT. NO REMUNERATIVA 6%", DestinoAlias.CONCEPTO, EXTRAORDINARIA.getId()),
            new AliasEscala("VIATICOS", DestinoAlias.CONCEPTO, VIATICO.getId()),
            new AliasEscala("HORAS AL 50 %", DestinoAlias.CONCEPTO, AL_50.getId()),
            new AliasEscala("HORAS AL 100%", DestinoAlias.CONCEPTO, AL_100.getId()),
            new AliasEscala("HORAS NORMALES", DestinoAlias.HORA_NORMAL, null),
            new AliasEscala("VALORES NETOS", DestinoAlias.VALORES_NETOS, null));

    /** Las filas de la hoja inventada, en el orden impreso; por mes: oct-26 y nov-26. */
    static final List<String> FILAS = List.of("BASICO", "PRESENTISMO", "A.I.S.", "ASIG. EXT. NO REMUNERATIVA",
            "ASIG. EXT. NO REMUNERATIVA 6%", "VIATICOS", "HORAS NORMALES", "HORAS AL 50 %", "HORAS AL 100%",
            "VALORES NETOS", "ART. 13", "ART. 28", "ART. 13 Y 28");
    static final List<String> OCTUBRE = List.of("$ 500.000,00", "$ 100.000,00", "$ 50.000,00", "$ 80.000,00",
            "$ 75.200,00", "$ 200.000,00", "$ 3.250,00", "$ 4.875,10", "$ 6.500,20", "$ 795.200,00", "$ 12.345,67",
            "$ 23.456,78", "$ 34.567,89");
    static final List<String> NOVIEMBRE = List.of("$ 545.000,00", "$ 101.500,00", "$ 52.000,00", "$ 81.000,00",
            "$ 76.000,00", "$ 205.000,00", "$ 3.542,50", "$ 5.313,79", "$ 7.085,03", "$ 839.800,00", "$ 13.000,00",
            "$ 24.000,00", "$ 35.000,00");

    private EscalaDePrueba() {
    }

    static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo, Integer num, Integer den) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, num, den, (int) id));
    }

    static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** La escala de agosto (la anterior a la hoja), con el CCT y los descuentos que la hoja no trae. */
    static EscalaSueldo agosto() {
        return new EscalaSueldo(LocalDate.of(2026, 8, 1), bd("3000"), Map.ofEntries(
                Map.entry(BASICO.getId(), bd("460000")),
                Map.entry(PRESENTISMO.getId(), bd("95000")),
                Map.entry(CCT.getId(), bd("98985")),
                Map.entry(AIS.getId(), bd("48000")),
                Map.entry(VIATICO.getId(), bd("190000")),
                Map.entry(EXTRAORDINARIA.getId(), bd("70000")),
                Map.entry(JUBILACION.getId(), bd("11")),
                Map.entry(OBRA_SOCIAL.getId(), bd("3")),
                Map.entry(LEY.getId(), bd("3")),
                Map.entry(SINDICATO.getId(), bd("3"))));
    }

    /** La hoja inventada de dos meses, como queda después de leerla. */
    static HojaEscala hoja() {
        return new HojaEscala(List.of(columna(java.time.YearMonth.of(2026, 10), OCTUBRE),
                columna(java.time.YearMonth.of(2026, 11), NOVIEMBRE)));
    }

    static HojaEscala.Columna columna(java.time.YearMonth mes, List<String> valores) {
        List<HojaEscala.Fila> filas = new java.util.ArrayList<>();
        for (int i = 0; i < FILAS.size(); i++) {
            filas.add(HojaEscala.Fila.de(FILAS.get(i), valores.get(i)));
        }
        return new HojaEscala.Columna(mes, filas);
    }

    /** Repositorios simulados: conceptos y alias de la migración, y agosto como la única escala. */
    record Repos(ConceptoSueldoRepository conceptos, EscalaSueldoRepository escalas, AliasEscalaRepository alias,
            SueldoService sueldo) {

        ImportacionEscala importacion() {
            return new ImportacionEscala(conceptos, escalas, alias, sueldo);
        }
    }

    static Repos repos() {
        ConceptoSueldoRepository conceptos = mock(ConceptoSueldoRepository.class);
        EscalaSueldoRepository escalas = mock(EscalaSueldoRepository.class);
        AliasEscalaRepository alias = mock(AliasEscalaRepository.class);
        when(conceptos.findAllByOrderByOrdenAscIdAsc()).thenReturn(CONCEPTOS);
        when(alias.findAll()).thenReturn(ALIAS);
        when(alias.findAllByOrderByIdAsc()).thenReturn(ALIAS);
        when(escalas.findByVigenteDesde(any())).thenReturn(Optional.empty());
        when(escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(agosto()));
        return new Repos(conceptos, escalas, alias, mock(SueldoService.class));
    }

    /**
     * Un Excel inventado con el formato de la hoja: título, encabezado con los meses y una fila por concepto.
     *
     * @param formulaEn una fila (por nombre) cuyo valor de octubre es una fórmula, o null
     */
    static byte[] excelDeLaHoja(String formulaEn) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Recomposición");
            hoja.createRow(0).createCell(0).setCellValue("RECOMPOSICIÓN SALARIAL - OFICIAL DE PRIMERA");
            Row encabezado = hoja.createRow(2);
            encabezado.createCell(0).setCellValue("JORNADA COMPLETA");
            encabezado.createCell(1).setCellValue("oct-26");
            encabezado.createCell(2).setCellValue("nov-26");
            for (int i = 0; i < FILAS.size(); i++) {
                Row fila = hoja.createRow(3 + i);
                fila.createCell(0).setCellValue(FILAS.get(i));
                if (FILAS.get(i).equals(formulaEn)) {
                    fila.createCell(1).setCellFormula("1+1");
                } else {
                    // Octubre como número (como lo guarda Excel) y noviembre como texto con formato de plata.
                    fila.createCell(1).setCellValue(ImporteHoja.leer(OCTUBRE.get(i)).orElseThrow().doubleValue());
                }
                fila.createCell(2).setCellValue(NOVIEMBRE.get(i));
            }
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** La plantilla de dos columnas (nombre y valor) de un solo mes, con o sin la fila "Mes". */
    static byte[] excelDeDosColumnas(String mes) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Escala");
            int n = 0;
            Row titulos = hoja.createRow(n++);
            titulos.createCell(0).setCellValue("Concepto");
            titulos.createCell(1).setCellValue("Valor");
            if (mes != null) {
                Row fila = hoja.createRow(n++);
                fila.createCell(0).setCellValue("Mes");
                fila.createCell(1).setCellValue(mes);
            }
            for (int i = 0; i < FILAS.size(); i++) {
                Row fila = hoja.createRow(n++);
                fila.createCell(0).setCellValue(FILAS.get(i));
                fila.createCell(1).setCellValue(OCTUBRE.get(i));
            }
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
