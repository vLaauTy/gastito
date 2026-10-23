package ar.gastito.ticket;

/**
 * Lee la orientación EXIF de un JPEG (el tag 0x0112 del IFD0). Java no la aplica al abrir la imagen, y una foto
 * mandada como archivo desde el celular suele venir "de costado" con la orientación en el EXIF.
 * Ante cualquier cosa rara devuelve {@link #NORMAL}: una foto mal girada se lee peor, pero se lee.
 */
final class OrientacionExif {

    static final int NORMAL = 1;
    private static final int TAG_ORIENTACION = 0x0112;

    private OrientacionExif() {
    }

    /** @return de 1 a 8, como define EXIF */
    static int leer(byte[] jpeg) {
        try {
            return buscar(jpeg);
        } catch (IndexOutOfBoundsException e) {
            return NORMAL;
        }
    }

    private static int buscar(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return NORMAL; // no es un JPEG
        }
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return NORMAL;
            }
            int marcador = b[i + 1] & 0xFF;
            if (marcador == 0xDA || marcador == 0xD9) {
                return NORMAL; // empiezan los datos de la imagen: no hubo EXIF
            }
            int largo = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marcador == 0xE1 && largo >= 8 && esExif(b, i + 4)) {
                return orientacion(b, i + 10);
            }
            i += 2 + largo;
        }
        return NORMAL;
    }

    private static boolean esExif(byte[] b, int i) {
        return b[i] == 'E' && b[i + 1] == 'x' && b[i + 2] == 'i' && b[i + 3] == 'f' && b[i + 4] == 0 && b[i + 5] == 0;
    }

    /** @param tiff dónde empieza el encabezado TIFF ("II" o "MM") */
    private static int orientacion(byte[] b, int tiff) {
        boolean intel = b[tiff] == 'I' && b[tiff + 1] == 'I';
        if (!intel && !(b[tiff] == 'M' && b[tiff + 1] == 'M')) {
            return NORMAL;
        }
        int ifd = tiff + entero(b, tiff + 4, intel);
        int entradas = corto(b, ifd, intel);
        for (int e = 0; e < entradas; e++) {
            int entrada = ifd + 2 + e * 12;
            if (corto(b, entrada, intel) == TAG_ORIENTACION) {
                int valor = corto(b, entrada + 8, intel);
                return valor >= 1 && valor <= 8 ? valor : NORMAL;
            }
        }
        return NORMAL;
    }

    private static int corto(byte[] b, int i, boolean intel) {
        return intel
                ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int entero(byte[] b, int i, boolean intel) {
        return intel
                ? corto(b, i, true) | (corto(b, i + 2, true) << 16)
                : (corto(b, i, false) << 16) | corto(b, i + 2, false);
    }
}
