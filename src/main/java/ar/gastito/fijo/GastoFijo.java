package ar.gastito.fijo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * Un gasto que se repite (el seguro, el streaming, la luz): cada mes, o cada dos, el día del cobro se crea el
 * movimiento pendiente y el bot pregunta si se cobró. Si el mes no tiene ese día (un 31), se cobra el último.
 */
@Entity
@Table(name = "gasto_fijo")
public class GastoFijo {

    static final int LARGO_MAXIMO_NOMBRE = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    /** El último monto confirmado (o el de alta); en los variables se propone el promedio de los últimos cobros. */
    private BigDecimal montoEsperado;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medio_pago_id")
    private MedioPago medioPago;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tarjeta_id")
    private TarjetaCredito tarjeta;

    private int diaDelMes;

    @Enumerated(EnumType.STRING)
    private Frecuencia frecuencia;

    /** El primer mes que se cobra (día 1). Los bimestrales van cada dos meses desde acá. */
    private LocalDate mesInicio;

    /** La luz o el gas: el monto cambia todos los meses. */
    private boolean montoVariable;

    private boolean activo = true;

    private LocalDateTime creadoEn;

    protected GastoFijo() {
    }

    public GastoFijo(String nombre, BigDecimal montoEsperado, Categoria categoria, MedioPago medioPago, Cuenta cuenta,
            TarjetaCredito tarjeta, int diaDelMes, Frecuencia frecuencia, YearMonth mesInicio, boolean montoVariable,
            LocalDateTime ahora) {
        if (diaDelMes < 1 || diaDelMes > 31) {
            throw new IllegalArgumentException("El día del cobro tiene que ser del 1 al 31");
        }
        String limpio = nombre.strip();
        this.nombre = limpio.length() > LARGO_MAXIMO_NOMBRE ? limpio.substring(0, LARGO_MAXIMO_NOMBRE) : limpio;
        this.montoEsperado = montoEsperado;
        this.categoria = categoria;
        this.medioPago = medioPago;
        this.cuenta = cuenta;
        this.tarjeta = tarjeta;
        this.diaDelMes = diaDelMes;
        this.frecuencia = frecuencia;
        this.mesInicio = mesInicio.atDay(1);
        this.montoVariable = montoVariable;
        this.creadoEn = ahora;
    }

    // --- Calendario ---

    /** Si este mes hay cobro: desde el mes de inicio, todos los meses o cada dos. */
    public boolean seCobraEn(YearMonth mes) {
        YearMonth inicio = YearMonth.from(mesInicio);
        if (mes.isBefore(inicio)) {
            return false;
        }
        return ChronoUnit.MONTHS.between(inicio, mes) % frecuencia.getMeses() == 0;
    }

    /** El día del cobro en ese mes: el día del fijo, o el último del mes si no lo tiene. */
    public LocalDate diaDeCobro(YearMonth mes) {
        return mes.atDay(Math.min(diaDelMes, mes.lengthOfMonth()));
    }

    /** El próximo cobro a partir de hoy (incluido), o null si está inactivo. */
    public LocalDate proximoCobro(LocalDate hoy) {
        if (!activo) {
            return null;
        }
        YearMonth mes = YearMonth.from(hoy).isBefore(YearMonth.from(mesInicio)) ? YearMonth.from(mesInicio)
                : YearMonth.from(hoy);
        for (int i = 0; i < 3; i++, mes = mes.plusMonths(1)) {
            if (seCobraEn(mes) && !diaDeCobro(mes).isBefore(hoy)) {
                return diaDeCobro(mes);
            }
        }
        return null;
    }

    // --- Cambios ---

    /** Se confirmó un cobro de un fijo de monto fijo: lo próximo que se propone es ese monto. */
    void cobrado(BigDecimal monto) {
        if (!montoVariable) {
            this.montoEsperado = monto;
        }
    }

    void darDeBaja() {
        this.activo = false;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public BigDecimal getMontoEsperado() {
        return montoEsperado;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public Cuenta getCuenta() {
        return cuenta;
    }

    public TarjetaCredito getTarjeta() {
        return tarjeta;
    }

    public int getDiaDelMes() {
        return diaDelMes;
    }

    public Frecuencia getFrecuencia() {
        return frecuencia;
    }

    public YearMonth getMesInicio() {
        return YearMonth.from(mesInicio);
    }

    public boolean isMontoVariable() {
        return montoVariable;
    }

    public boolean isActivo() {
        return activo;
    }
}
