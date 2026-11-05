package ar.gastito.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Agregar, editar y dar de baja tarjetas desde el dashboard, sin tocar el historial. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(CatalogoService.class)
class TarjetasAbmTest {

    @Autowired
    CatalogoService catalogos;

    @Autowired
    CuentaRepository cuentas;

    private long galicia() {
        return cuentas.findByActivaTrueOrderById().getFirst().getId();
    }

    @Test
    void alArrancarEstanLaMasterCardGoldYLaDeMercadoPago() {
        assertThat(catalogos.cargar().tarjetas()).extracting(TarjetaCredito::getNombre)
                .containsExactlyInAnyOrder("MasterCard Gold", "Mercado Pago");
    }

    @Test
    void unaTarjetaNuevaQuedaActivaConSusAlias() {
        TarjetaCredito visa = catalogos.altaTarjeta(new CatalogoService.DatosTarjeta("Visa Signature", "Galicia",
                galicia(), "#1A1F71", "v", " Visa, La VISA de Galicia ,"));

        assertThat(visa.alias()).containsExactly("visa", "la visa de galicia");
        assertThat(visa.getInicial()).isEqualTo("V");
        assertThat(visa.getCuentaDebito().getNombre()).isEqualTo("Galicia");
        assertThat(catalogos.cargar().tarjetas()).extracting(TarjetaCredito::getNombre).contains("Visa Signature");
    }

    @Test
    void noHayDosTarjetasConElMismoNombre() {
        assertThatThrownBy(() -> catalogos.altaTarjeta(new CatalogoService.DatosTarjeta("mastercard gold", null,
                null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void darDeBajaLaSacaDeLasActivasPeroQuedaEnLaLista() {
        TarjetaCredito mp = catalogos.cargar().tarjetas().stream().filter(t -> t.getNombre().equals("Mercado Pago"))
                .findFirst().orElseThrow();

        catalogos.bajaTarjeta(mp.getId());

        assertThat(catalogos.cargar().tarjetas()).extracting(TarjetaCredito::getNombre)
                .containsExactly("MasterCard Gold");
        assertThat(catalogos.cargar().tarjetaUnica()).isPresent();
        assertThat(catalogos.todasLasTarjetas()).extracting(TarjetaCredito::getNombre)
                .containsExactly("MasterCard Gold", "Mercado Pago");

        catalogos.reactivarTarjeta(mp.getId());
        assertThat(catalogos.cargar().tarjetas()).hasSize(2);
    }

    @Test
    void editarCambiaElNombreElColorYLosAlias() {
        TarjetaCredito mp = catalogos.cargar().tarjetas().stream().filter(t -> t.getNombre().equals("Mercado Pago"))
                .findFirst().orElseThrow();

        TarjetaCredito editada = catalogos.editarTarjeta(mp.getId(), new CatalogoService.DatosTarjeta(
                "Mercado Pago Mastercard", "Mercado Pago", mp.getCuentaDebito().getId(), "#00B1EA", "MP", "mp, meli"));

        assertThat(editada.getNombre()).isEqualTo("Mercado Pago Mastercard");
        assertThat(editada.getColor()).isEqualTo("#00B1EA");
        assertThat(editada.alias()).containsExactly("mp", "meli");
        assertThatThrownBy(() -> catalogos.editarTarjeta(mp.getId(), new CatalogoService.DatosTarjeta("Nueva", null,
                null, "rojo", null, null))).isInstanceOf(IllegalArgumentException.class);
    }
}
