package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.CREDITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.DEBITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.GALICIA;
import static ar.gastito.interpretacion.CatalogoDePrueba.TRANSPORTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.movimiento.ClaseIngreso;

/**
 * Las intenciones que no son un gasto: la respuesta de la IA simulada llega como el JSON real (los campos que no
 * menciona, ausentes).
 */
class ValidadorIntencionesTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);
    static final TarjetaCredito MASTERCARD = mock(TarjetaCredito.class);

    private final ValidadorInterpretacion validador = new ValidadorInterpretacion(new ResolvedorFechas());
    private final Catalogo base = CatalogoDePrueba.crear();
    private final Catalogo catalogo = new Catalogo(base.categorias(), base.mediosPago(), base.cuentas(),
            Optional.of(MASTERCARD));

    private ValidadorInterpretacion.Resultado validar(String json) {
        try {
            return validador.validar(new ObjectMapper().readValue(json, InterpretacionIa.class), catalogo, AHORA);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void elAltaDeUnFijoResuelveLaTarjetaYGuardaComoLoNombro() {
        PedidoFijo p = ((ValidadorInterpretacion.AltaFijo) validar("""
                {"intencion": "ALTA_FIJO", "monto": "32000", "descripcion": "Seguro moto",
                 "categorias": ["Transporte / Moto"], "confianza": "alta", "medioPago": "Tarjeta de crédito",
                 "fijoAlias": "seguro de la moto", "fijoFrecuencia": "MENSUAL", "fijoVariable": "no"}""")).pedido();

        assertThat(p.nombre()).isEqualTo("Seguro moto");
        assertThat(p.monto()).isEqualByComparingTo("32000");
        assertThat(p.medioPago()).isEqualTo(CREDITO);
        assertThat(p.tarjeta()).isSameAs(MASTERCARD);
        assertThat(p.cuenta()).isNull();
        assertThat(p.categoria()).isEqualTo(TRANSPORTE);
        assertThat(p.diaDelMes()).isNull();
        assertThat(p.alias()).isEqualTo("seguro de la moto");
        assertThat(p.variable()).isFalse();
    }

    @Test
    void elAliasIgualAlNombreNoSeGuardaYElNombreViejoDeLaIntencionSigueAndando() {
        PedidoFijo p = ((ValidadorInterpretacion.AltaFijo) validar("""
                {"intencion": "FIJO", "descripcion": "Netflix", "fijoAlias": "netflix", "fijoDia": "7"}""")).pedido();

        assertThat(p.alias()).isNull();
        assertThat(p.diaDelMes()).isEqualTo(7);
        assertThat(p.monto()).isNull();
    }

    @Test
    void unIngresoTraeSuClaseYLaCuenta() {
        ValidadorInterpretacion.Ingreso i = (ValidadorInterpretacion.Ingreso) validar("""
                {"intencion": "INGRESO", "claseIngreso": "SUELDO", "monto": "980000", "cuenta": "Galicia",
                 "descripcion": "Sueldo de octubre"}""");

        assertThat(i.clase()).isEqualTo(ClaseIngreso.SUELDO);
        assertThat(i.monto()).isEqualByComparingTo("980000");
        assertThat(i.cuenta()).isEqualTo(GALICIA);
        assertThat(i.descripcion()).as("solo los extras llevan descripción").isNull();

        ValidadorInterpretacion.Ingreso extra = (ValidadorInterpretacion.Ingreso) validar("""
                {"intencion": "INGRESO", "claseIngreso": "cualquiera", "descripcion": "Arreglo de PC"}""");
        assertThat(extra.clase()).isEqualTo(ClaseIngreso.EXTRA);
        assertThat(extra.monto()).isNull();
        assertThat(extra.descripcion()).isEqualTo("Arreglo de PC");
    }

    @Test
    void corregirTraeSoloElCampoQueCambiaYSiNoLoDiceLoDeduce() {
        ValidadorInterpretacion.Correccion monto = (ValidadorInterpretacion.Correccion) validar("""
                {"intencion": "CORREGIR", "campo": "MONTO", "monto": "3500"}""");
        assertThat(monto.campo()).isEqualTo(ValidadorInterpretacion.Campo.MONTO);
        assertThat(monto.monto()).isEqualByComparingTo("3500");

        ValidadorInterpretacion.Correccion medio = (ValidadorInterpretacion.Correccion) validar("""
                {"intencion": "CORREGIR", "medioPago": "Débito"}""");
        assertThat(medio.campo()).isEqualTo(ValidadorInterpretacion.Campo.MEDIO);
        assertThat(medio.medio()).isEqualTo(DEBITO);

        ValidadorInterpretacion.Correccion nada = (ValidadorInterpretacion.Correccion) validar("""
                {"intencion": "CORREGIR", "campo": "MONTO", "monto": "-5"}""");
        assertThat(nada.campo()).isNull();
    }

    @Test
    void losDemasPedidos() {
        assertThat(validar("{\"intencion\": \"BORRAR\"}")).isInstanceOf(ValidadorInterpretacion.Borrar.class);
        assertThat(validar("{\"intencion\": \"VER\", \"ver\": \"FIJOS\"}"))
                .isEqualTo(new ValidadorInterpretacion.Ver(ValidadorInterpretacion.Lista.FIJOS));
        assertThat(validar("{\"intencion\": \"VER\", \"ver\": \"cualquier cosa\"}"))
                .as("una lista que no existe es una pregunta").isInstanceOf(ValidadorInterpretacion.Pregunta.class);
        assertThat(validar("{\"intencion\": \"CALCULO_SUELDO\", \"horas\": \"10 al 50, 30 nocturnas\"}"))
                .isEqualTo(new ValidadorInterpretacion.CalculoSueldo("10 al 50, 30 nocturnas"));
        assertThat(validar("{\"intencion\": \"CANCELAR_CUOTAS\", \"descripcion\": \"Zapatillas\"}"))
                .isEqualTo(new ValidadorInterpretacion.CancelarCuotas("Zapatillas"));
        assertThat(validar("{\"intencion\": \"PERDONAR\", \"devolucionPersona\": \"Pablo\"}"))
                .isEqualTo(new ValidadorInterpretacion.Perdonar("Pablo", null));
        assertThat(validar("{\"intencion\": \"BAJA_FIJO\", \"descripcion\": \"Netflix\"}"))
                .isEqualTo(new ValidadorInterpretacion.BajaFijo("Netflix"));
    }

    @Test
    void unDudosoTraeLasOpcionesValidasOLasDeSiempre() {
        ValidadorInterpretacion.Dudosa d = (ValidadorInterpretacion.Dudosa) validar("""
                {"intencion": "DUDOSO", "monto": "32000", "descripcion": "Seguro",
                 "opciones": ["ALTA_FIJO", "GASTO", "ALTA_FIJO", "OTRO", "INGRESO", "PREGUNTA"]}""");
        assertThat(d.opciones()).containsExactly(Intencion.ALTA_FIJO, Intencion.GASTO, Intencion.INGRESO);
        assertThat(d.comoGasto().movimiento().monto()).isEqualByComparingTo("32000");

        ValidadorInterpretacion.Dudosa sinOpciones = (ValidadorInterpretacion.Dudosa) validar("""
                {"intencion": "DUDOSO", "opciones": ["GASTO"]}""");
        assertThat(sinOpciones.opciones()).isEqualTo(List.of(Intencion.GASTO, Intencion.PREGUNTA));
    }
}
