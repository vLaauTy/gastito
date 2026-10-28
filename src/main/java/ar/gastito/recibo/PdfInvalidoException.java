package ar.gastito.recibo;

/** El archivo no es un PDF que se pueda abrir (dañado, con contraseña o de otro tipo). */
public class PdfInvalidoException extends RuntimeException {

    public PdfInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
