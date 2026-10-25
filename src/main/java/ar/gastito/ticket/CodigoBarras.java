package ar.gastito.ticket;

import java.util.Optional;

/**
 * El código de barras de un producto: EAN-8 o EAN-13 con el dígito verificador correcto. Cualquier otra cosa
 * (un número de artículo interno, un código mal leído) se descarta. No es un dato personal: identifica un
 * producto, no a una persona.
 */
final class CodigoBarras {

    private CodigoBarras() {
    }

    /** Sin espacios ni guiones; vacío si no es un EAN válido. */
    static Optional<String> validar(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String codigo = texto.replaceAll("[\\s-]", "");
        if (!codigo.matches("\\d{8}|\\d{13}") || !verificadorCorrecto(codigo)) {
            return Optional.empty();
        }
        return Optional.of(codigo);
    }

    /**
     * Desde la derecha, sin contar el verificador, los dígitos se multiplican por 3, 1, 3, 1… La suma más el
     * verificador tiene que ser múltiplo de 10. Vale igual para EAN-8 y EAN-13.
     */
    static boolean verificadorCorrecto(String codigo) {
        int suma = 0;
        int peso = 3;
        for (int i = codigo.length() - 2; i >= 0; i--) {
            suma += (codigo.charAt(i) - '0') * peso;
            peso = peso == 3 ? 1 : 3;
        }
        int verificador = codigo.charAt(codigo.length() - 1) - '0';
        return (suma + verificador) % 10 == 0;
    }
}
