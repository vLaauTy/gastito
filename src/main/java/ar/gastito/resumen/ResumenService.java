package ar.gastito.resumen;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ControlNumeros;

/**
 * Arma los resúmenes (Java calcula, la IA redacta) y lleva la cuenta de los envíos automáticos: cuál período ya
 * se mandó, para no repetirlo ni salteárselo aunque la PC haya estado apagada.
 * <ul>
 * <li>La primera vez que corre, el período vencido se registra como omitido: no se manda historia.</li>
 * <li>Si hay varios pendientes, se manda solo el más reciente de cada tipo; los otros quedan ofrecidos en
 * "Ver anteriores".</li>
 * </ul>
 */
@Service
public class ResumenService {

    private static final Logger log = LoggerFactory.getLogger(ResumenService.class);

    /** Cuántos períodos anteriores se ofrecen como mucho (los más viejos se omiten). */
    static final int MAXIMO_ANTERIORES = 12;

    private final CalculoResumen calculo;
    private final RedactorResumen redactor;
    private final ResumenRepository resumenes;
    private final EnvioResumenRepository envios;
    private final ObjectMapper json;
    private final GastitoProperties.Resumenes config;
    private final Clock clock;

    public ResumenService(CalculoResumen calculo, RedactorResumen redactor, ResumenRepository resumenes,
            EnvioResumenRepository envios, ObjectMapper json, GastitoProperties properties, Clock clock) {
        this.calculo = calculo;
        this.redactor = redactor;
        this.resumenes = resumenes;
        this.envios = envios;
        this.json = json;
        this.config = properties.resumenes();
        this.clock = clock;
    }

    /** Un resumen guardado y sus datos, listos para mostrar. */
    public record Armado(Resumen resumen, DatosResumen datos) {
    }

    /**
     * Un envío automático pendiente.
     *
     * @param anteriores los períodos que también estaban pendientes y quedan en "Ver anteriores"
     */
    public record Envio(PeriodoResumen periodo, List<PeriodoResumen> anteriores) {
    }

    // --- Armar ---

    /** El período en curso hasta hoy (/resumen y /resumen mes). Si no terminó, es parcial. */
    public Armado pedido(TipoResumen tipo) {
        return armar(PeriodoResumen.de(tipo, LocalDate.now(clock)), false);
    }

    /** Calcula, le pide el texto a la IA y lo guarda. No es transaccional: la llamada a la IA puede tardar. */
    public Armado armar(PeriodoResumen periodo, boolean automatico) {
        LocalDate hoy = LocalDate.now(clock);
        DatosResumen datos = calculo.calcular(periodo, hoy);
        String datosJson = aJson(datos);
        Set<BigDecimal> permitidos = ControlNumeros.numerosDe(json.valueToTree(datos));
        RedactorResumen.Redaccion texto = redactor.redactar(periodo.tipo(), datosJson, permitidos);
        Resumen resumen = resumenes.save(new Resumen(periodo.tipo(), periodo.desde(), datos.periodo().hasta(),
                datos.periodo().parcial(), automatico, datosJson, texto.observaciones(), texto.consejo(),
                texto.descartado(), texto.usoIaId(), LocalDateTime.now(clock)));
        return new Armado(resumen, datos);
    }

    @Transactional(readOnly = true)
    public Optional<Armado> ultimo() {
        return resumenes.findFirstByOrderByCreadoEnDescIdDesc().map(r -> new Armado(r, datos(r)));
    }

    public DatosResumen datos(Resumen resumen) {
        try {
            return json.readValue(resumen.getDatos(), DatosResumen.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Los datos del resumen " + resumen.getId() + " no se pueden leer", e);
        }
    }

    // --- Envíos automáticos ---

    /** Lo que hay que mandar ahora: a lo sumo uno por tipo, el período vencido más reciente. */
    @Transactional
    public List<Envio> pendientes(LocalDateTime ahora) {
        List<Envio> pendientes = new ArrayList<>();
        for (TipoResumen tipo : TipoResumen.values()) {
            PeriodoResumen ultimo = PeriodoResumen.ultimoVencido(tipo, ahora, config.horaSemanal(), config.horaMensual());
            Optional<EnvioResumen> registrado = envios.findFirstByTipoOrderByDesdeDesc(tipo);
            if (registrado.isEmpty()) {
                // Primera vez: no se manda historia.
                envios.save(new EnvioResumen(ultimo, EnvioResumen.Estado.OMITIDO, null, ahora));
                log.info("Primer arranque: el resumen {} no se manda", ultimo);
                continue;
            }
            if (!registrado.get().getDesde().isBefore(ultimo.desde())) {
                continue;
            }
            List<PeriodoResumen> anteriores = new ArrayList<>();
            for (PeriodoResumen p = registrado.get().periodo().siguiente(); p.desde().isBefore(ultimo.desde());
                    p = p.siguiente()) {
                anteriores.add(p);
            }
            pendientes.add(new Envio(ultimo, anteriores));
        }
        return pendientes;
    }

    /**
     * Se mandó el resumen de un envío: queda registrado y los anteriores quedan ofrecidos (los más viejos que
     * {@value #MAXIMO_ANTERIORES}, omitidos).
     */
    @Transactional
    public void registrarEnvio(Envio envio, Resumen resumen) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        int omitidos = Math.max(0, envio.anteriores().size() - MAXIMO_ANTERIORES);
        for (int i = 0; i < envio.anteriores().size(); i++) {
            envios.save(new EnvioResumen(envio.anteriores().get(i),
                    i < omitidos ? EnvioResumen.Estado.OMITIDO : EnvioResumen.Estado.OFRECIDO, null, ahora));
        }
        envios.save(new EnvioResumen(envio.periodo(), EnvioResumen.Estado.ENVIADO, resumen.getId(), ahora));
        marcarEnviado(resumen.getId());
    }

    /** Cuántos anteriores se ofrecen en el mensaje ("Tenés N resúmenes anteriores sin ver"). */
    public static int ofrecidos(Envio envio) {
        return Math.min(envio.anteriores().size(), MAXIMO_ANTERIORES);
    }

    /**
     * "Ver anteriores": arma los resúmenes ofrecidos de ese tipo entre las dos fechas, del más viejo al más nuevo,
     * y los registra como enviados.
     */
    public List<Armado> anteriores(TipoResumen tipo, LocalDate desde, LocalDate hasta) {
        List<EnvioResumen> ofrecidos = envios.findByTipoAndEstadoAndDesdeBetweenOrderByDesde(tipo,
                EnvioResumen.Estado.OFRECIDO, desde, hasta);
        List<Armado> armados = new ArrayList<>();
        for (EnvioResumen ofrecido : ofrecidos) {
            Armado armado = armar(ofrecido.periodo(), true);
            confirmarAnterior(ofrecido.getTipo(), ofrecido.getDesde(), armado.resumen().getId());
            armados.add(armado);
        }
        return armados;
    }

    /** Con save explícito: se llama también desde adentro de la clase, donde @Transactional no aplica. */
    public void marcarEnviado(Long resumenId) {
        resumenes.findById(resumenId).ifPresent(r -> {
            r.marcarEnviado(LocalDateTime.now(clock));
            resumenes.save(r);
        });
    }

    private void confirmarAnterior(TipoResumen tipo, LocalDate desde, Long resumenId) {
        envios.findByTipoAndDesde(tipo, desde).ifPresent(e -> {
            e.enviado(resumenId, LocalDateTime.now(clock));
            envios.save(e);
        });
        marcarEnviado(resumenId);
    }

    private String aJson(DatosResumen datos) {
        try {
            return json.writeValueAsString(datos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudieron pasar a JSON los datos del resumen", e);
        }
    }
}
