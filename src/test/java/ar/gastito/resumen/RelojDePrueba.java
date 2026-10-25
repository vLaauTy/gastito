package ar.gastito.resumen;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Un reloj que el test puede mover: "la PC se prende el martes 10/11". */
public class RelojDePrueba extends Clock {

    private final ZoneId zona;
    private Instant ahora;

    public RelojDePrueba(LocalDateTime ahora, ZoneId zona) {
        this.zona = zona;
        this.ahora = ahora.atZone(zona).toInstant();
    }

    public void ir(LocalDateTime cuando) {
        this.ahora = cuando.atZone(zona).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId otra) {
        return new RelojDePrueba(LocalDateTime.ofInstant(ahora, otra), otra);
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
