package ar.gastito.sueldo.escala;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Lee la escala de un Excel, sin IA. Acepta dos formatos, en la primera hoja:
 * <ul>
 * <li>El de la hoja del sindicato: el nombre de la fila en la columna A y una columna por mes, con el mes en el
 * encabezado ("oct-26", o una fecha).</li>
 * <li>Una plantilla de dos columnas (nombre y valor) de un solo mes. El mes sale de una fila "Mes" si la hay; si no,
 * la columna queda sin mes y se pregunta.</li>
 * </ul>
 * Las celdas con fórmulas se ignoran: solo cuentan los valores escritos.
 */
@Component
public class LectorExcelEscala {

    /** Las filas que dicen de qué mes es la plantilla de dos columnas. */
    private static final Set<String> FILAS_DE_MES = Set.of("mes", "periodo", "vigentedesde", "rigedesde");
    private static final int MAXIMO_FILAS = 300;

    /** @throws IllegalArgumentException si no es un Excel que se pueda abrir o no tiene filas con valores */
    public HojaEscala leer(byte[] archivo) {
        Workbook libro;
        try {
            libro = WorkbookFactory.create(new ByteArrayInputStream(archivo));
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("El Excel tiene contraseña: guardalo sin contraseña");
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("No pude abrir el archivo: tiene que ser un Excel (.xlsx o .xls)");
        }
        try (libro) {
            if (libro.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("El Excel no tiene hojas");
            }
            Sheet hoja = libro.getSheetAt(0);
            Optional<Row> encabezado = encabezado(hoja);
            HojaEscala leida = encabezado.isPresent() ? porMes(hoja, encabezado.get()) : dosColumnas(hoja);
            if (leida.columnas().isEmpty() || leida.columnas().stream().allMatch(c -> c.filas().isEmpty())) {
                throw new IllegalArgumentException("No encontré valores en el Excel: tiene que tener el nombre de "
                        + "cada fila en la columna A y los valores al lado");
            }
            return leida;
        } catch (IOException e) {
            throw new IllegalArgumentException("No pude leer el Excel");
        }
    }

    /** La primera fila que tiene meses de la columna B en adelante. */
    private static Optional<Row> encabezado(Sheet hoja) {
        for (Row fila : hoja) {
            if (fila.getRowNum() > MAXIMO_FILAS) {
                break;
            }
            for (Cell celda : fila) {
                if (celda.getColumnIndex() > 0 && mes(celda).isPresent()) {
                    return Optional.of(fila);
                }
            }
        }
        return Optional.empty();
    }

    private static HojaEscala porMes(Sheet hoja, Row encabezado) {
        Map<Integer, YearMonth> meses = new LinkedHashMap<>();
        for (Cell celda : encabezado) {
            if (celda.getColumnIndex() > 0) {
                mes(celda).ifPresent(m -> meses.put(celda.getColumnIndex(), m));
            }
        }
        Map<Integer, List<HojaEscala.Fila>> filas = new LinkedHashMap<>();
        meses.keySet().forEach(col -> filas.put(col, new ArrayList<>()));
        for (Row fila : hoja) {
            if (fila.getRowNum() <= encabezado.getRowNum() || fila.getRowNum() > MAXIMO_FILAS) {
                continue;
            }
            String nombre = nombre(fila);
            if (nombre == null) {
                continue;
            }
            for (Integer col : meses.keySet()) {
                valor(fila.getCell(col)).ifPresent(texto -> filas.get(col).add(fila(nombre, texto)));
            }
        }
        return new HojaEscala(meses.entrySet().stream()
                .map(e -> new HojaEscala.Columna(e.getValue(), filas.get(e.getKey()))).toList());
    }

    private static HojaEscala dosColumnas(Sheet hoja) {
        YearMonth mes = null;
        List<HojaEscala.Fila> filas = new ArrayList<>();
        for (Row fila : hoja) {
            if (fila.getRowNum() > MAXIMO_FILAS) {
                break;
            }
            String nombre = nombre(fila);
            if (nombre == null) {
                continue;
            }
            Cell celda = fila.getCell(1);
            if (FILAS_DE_MES.contains(NombreFila.clave(nombre))) {
                Optional<YearMonth> deLaFila = celda == null ? Optional.empty() : mes(celda);
                if (deLaFila.isPresent()) {
                    mes = deLaFila.get();
                }
                continue;
            }
            valor(celda).ifPresent(texto -> filas.add(fila(nombre, texto)));
        }
        return new HojaEscala(List.of(new HojaEscala.Columna(mes, filas)));
    }

    private static HojaEscala.Fila fila(String nombre, String texto) {
        return HojaEscala.Fila.de(nombre, texto);
    }

    private static String nombre(Row fila) {
        Cell celda = fila.getCell(0);
        if (celda == null || celda.getCellType() != CellType.STRING) {
            return null;
        }
        String texto = celda.getStringCellValue().strip();
        return texto.isEmpty() ? null : texto;
    }

    /** El valor escrito en la celda, como texto ("558154.00" o "$ 558.154,00"); vacío si no hay o es fórmula. */
    private static Optional<String> valor(Cell celda) {
        if (celda == null) {
            return Optional.empty();
        }
        return switch (celda.getCellType()) {
            case NUMERIC -> DateUtil.isCellDateFormatted(celda) ? Optional.empty()
                    : Optional.of(BigDecimal.valueOf(celda.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP)
                            .toPlainString());
            case STRING -> Optional.of(celda.getStringCellValue().strip()).filter(t -> !t.isEmpty());
            default -> Optional.empty();
        };
    }

    private static Optional<YearMonth> mes(Cell celda) {
        if (celda.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(celda)) {
            return Optional.of(YearMonth.from(celda.getLocalDateTimeCellValue()));
        }
        if (celda.getCellType() == CellType.STRING) {
            return MesHoja.leer(celda.getStringCellValue());
        }
        return Optional.empty();
    }
}
