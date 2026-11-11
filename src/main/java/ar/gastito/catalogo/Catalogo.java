package ar.gastito.catalogo;

import java.util.List;
import java.util.Optional;

/**
 * Las opciones reales de la base contra las que se interpreta un mensaje.
 *
 * @param tarjetas las tarjetas de crédito activas, la última que se usó primero
 */
public record Catalogo(List<Categoria> categorias, List<MedioPago> mediosPago, List<Cuenta> cuentas,
        List<TarjetaCredito> tarjetas) {

    public Catalogo {
        tarjetas = tarjetas == null ? List.of() : List.copyOf(tarjetas);
    }

    /** Con una sola tarjeta (o ninguna). */
    public Catalogo(List<Categoria> categorias, List<MedioPago> mediosPago, List<Cuenta> cuentas,
            Optional<TarjetaCredito> tarjeta) {
        this(categorias, mediosPago, cuentas, tarjeta.map(List::of).orElse(List.of()));
    }

    public Categoria otros() {
        return categorias.stream()
                .filter(c -> Categoria.OTROS.equals(c.getNombre()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Falta la categoría " + Categoria.OTROS));
    }

    /** La tarjeta de un gasto con crédito que no dijo cuál: solo si hay una sola activa (si no, se pregunta). */
    public Optional<TarjetaCredito> tarjetaUnica() {
        return tarjetas.size() == 1 ? Optional.of(tarjetas.getFirst()) : Optional.empty();
    }

    /** Contado: todo lo que no es tarjeta de crédito (efectivo, débito, transferencia, QR). Es el medio por defecto. */
    public Optional<MedioPago> contado() {
        return mediosPago.stream().filter(MedioPago::isEfectivo).findFirst();
    }

    public Optional<MedioPago> medioCredito() {
        return mediosPago.stream().filter(MedioPago::esTarjetaDeCredito).findFirst();
    }

    public Optional<TarjetaCredito> tarjeta(Long id) {
        return id == null ? Optional.empty() : tarjetas.stream().filter(t -> id.equals(t.getId())).findFirst();
    }
}
