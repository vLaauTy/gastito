package ar.gastito.sueldo.escala;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.ConceptoSueldoRepository;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.EscalaSueldoRepository;
import ar.gastito.sueldo.SueldoProperties;

/**
 * La plantilla para descargar: el formato de la hoja del sindicato (nombre de fila en la columna A, una columna por
 * mes) con los valores de la escala vigente. Solo lleva las filas que tienen alias, que son las que trae la hoja.
 */
@Component
public class PlantillaEscala {

    private final ConceptoSueldoRepository conceptos;
    private final EscalaSueldoRepository escalas;
    private final AliasEscalaRepository aliases;
    private final SueldoProperties properties;

    public PlantillaEscala(ConceptoSueldoRepository conceptos, EscalaSueldoRepository escalas,
            AliasEscalaRepository aliases, SueldoProperties properties) {
        this.conceptos = conceptos;
        this.escalas = escalas;
        this.aliases = aliases;
        this.properties = properties;
    }

    /** @param mes el mes de la columna; los valores son los de la escala que rige ese mes (vacíos si no hay) */
    @Transactional(readOnly = true)
    public byte[] generar(YearMonth mes) {
        List<ConceptoSueldo> todos = conceptos.findAllByOrderByOrdenAscIdAsc();
        List<AliasEscala> todosLosAlias = aliases.findAllByOrderByIdAsc();
        Optional<EscalaSueldo> escala = escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(
                mes.atDay(1));

        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Escala");
            CellStyle negrita = libro.createCellStyle();
            Font fuente = libro.createFont();
            fuente.setBold(true);
            negrita.setFont(fuente);
            CellStyle plata = libro.createCellStyle();
            plata.setDataFormat(libro.createDataFormat().getFormat("\"$\" #,##0.00"));

            Row titulo = hoja.createRow(0);
            titulo.createCell(0).setCellValue("Recomposición salarial — Oficial de primera, jornada completa");
            titulo.getCell(0).setCellStyle(negrita);
            Row encabezado = hoja.createRow(1);
            encabezado.createCell(0).setCellValue("Concepto");
            encabezado.createCell(1).setCellValue(MesHoja.corto(mes));
            encabezado.getCell(0).setCellStyle(negrita);
            encabezado.getCell(1).setCellStyle(negrita);

            int fila = 2;
            fila =escribir(hoja, fila, primerAlias(todosLosAlias, DestinoAlias.HORA_NORMAL, null),
                    escala.map(EscalaSueldo::getValorHora).orElse(null), plata);
            Map<Long, BigDecimal> enLaPlantilla = new HashMap<>();
            for (ConceptoSueldo c : todos) {
                String alias = primerAlias(todosLosAlias, DestinoAlias.CONCEPTO, c.getId());
                if (alias == null || !c.isActivo()) {
                    continue;
                }
                BigDecimal valor = escala.map(e -> e.getValores().get(c.getId())).orElse(null);
                if (valor != null && valor.signum() > 0) {
                    enLaPlantilla.put(c.getId(), valor);
                }
                fila = escribir(hoja, fila, alias, valor, plata);
            }
            BigDecimal netos = escala.map(e -> ImportacionEscala.control(todos, enLaPlantilla,
                    properties.porcentajeControlHoja(), BigDecimal.ZERO).calculado()).orElse(null);
            escribir(hoja, fila, primerAlias(todosLosAlias, DestinoAlias.VALORES_NETOS, null), netos, plata);
            hoja.setColumnWidth(0, 40 * 256);
            hoja.setColumnWidth(1, 18 * 256);
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int escribir(Sheet hoja, int fila, String nombre, BigDecimal valor, CellStyle plata) {
        if (nombre == null) {
            return fila;
        }
        Row row = hoja.createRow(fila);
        row.createCell(0).setCellValue(nombre);
        if (valor != null) {
            row.createCell(1).setCellValue(valor.doubleValue());
            row.getCell(1).setCellStyle(plata);
        }
        return fila + 1;
    }

    private static String primerAlias(List<AliasEscala> todos, DestinoAlias destino, Long conceptoId) {
        return todos.stream().filter(a -> a.getDestino() == destino
                && (conceptoId == null || conceptoId.equals(a.getConceptoId())))
                .map(AliasEscala::getAlias).findFirst().orElse(null);
    }
}
