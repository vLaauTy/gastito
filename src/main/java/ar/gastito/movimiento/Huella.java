package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;

/**
 * Lo que se compara para saber si dos movimientos son la misma compra. Sirve igual para un gasto cargado a mano,
 * un ticket, un cobro de un gasto fijo o (en la etapa 7) un aviso del banco o un renglón del resumen de la tarjeta.
 *
 * @param monto       lo que salió de la billetera: en una juntada, el total pagado, no mi parte
 * @param categoriaId null si no tiene
 * @param comercio    null si no tiene ("DIA TIENDA 5601", "Día")
 */
public record Huella(Long id, LocalDateTime fechaHora, BigDecimal monto, Long categoriaId, String comercio) {

    /** El comercio sin mayúsculas, tildes, números de sucursal ni signos: "DIA TIENDA 5601" → "dia tienda". */
    public String comercioNormalizado() {
        if (comercio == null) {
            return null;
        }
        String sinTildes = Normalizer.normalize(comercio, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String limpio = sinTildes.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\b\\d+\\b", " ")
                .replaceAll("\\s+", " ").strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
