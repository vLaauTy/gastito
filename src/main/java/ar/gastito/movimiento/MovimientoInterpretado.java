package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * Lo que se entendió de un mensaje, ya validado contra la base, listo para guardar como pendiente.
 *
 * @param monto      null si el mensaje no lo dice (se pregunta en la tarjeta)
 * @param categoria  null si la confianza no es alta: el usuario elige entre las candidatas
 * @param candidatas categorías posibles en orden (vacía si la categoría ya está elegida)
 */
public record MovimientoInterpretado(
        BigDecimal monto,
        String descripcion,
        Categoria categoria,
        List<Categoria> candidatas,
        MedioPago medioPago,
        Cuenta cuenta,
        TarjetaCredito tarjeta,
        LocalDateTime fechaHora) {

    public MovimientoInterpretado {
        candidatas = candidatas == null ? List.of() : List.copyOf(candidatas);
    }

    /** Con la categoría ya elegida (confianza alta). */
    public static MovimientoInterpretado conCategoria(BigDecimal monto, String descripcion, Categoria categoria,
            MedioPago medioPago, Cuenta cuenta, TarjetaCredito tarjeta, LocalDateTime fechaHora) {
        return new MovimientoInterpretado(monto, descripcion, categoria, List.of(), medioPago, cuenta, tarjeta,
                fechaHora);
    }

    public MovimientoInterpretado conMonto(BigDecimal nuevoMonto) {
        return new MovimientoInterpretado(nuevoMonto, descripcion, categoria, candidatas, medioPago, cuenta, tarjeta,
                fechaHora);
    }

    public MovimientoInterpretado conCategoriaElegida(Categoria elegida) {
        return new MovimientoInterpretado(monto, descripcion, elegida, List.of(), medioPago, cuenta, tarjeta,
                fechaHora);
    }
}
