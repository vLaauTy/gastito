package ar.gastito.catalogo;

/** Algo que el usuario puede nombrar en un mensaje: categoría, medio de pago o cuenta. */
public interface ConPalabrasClave {

    String getNombre();

    /** Palabras sueltas, sin tildes, separadas por coma. Puede ser null. */
    String getPalabrasClave();
}
