'use strict';

/*
 * Sueldo en la sección Ingresos: el seguimiento mes por mes (calculado, cobrado y recibo), la calculadora y la
 * edición de escalas y conceptos. Igual que el resto del dashboard, solo pide los números a la API y los muestra:
 * la cuenta del sueldo, las diferencias y lo raro de un recibo llegan hechos.
 */
(() => {
  const { h, api, pesos, mostrarAviso, nombreMes, mesYAnio, mayuscula, numeroArg, fechaCorta, estado } = window.Gastito;
  const { Iconos } = window;
  const $ = (selector) => document.querySelector(selector);

  const JSON_HEADERS = { 'Content-Type': 'application/json' };
  const GRUPOS_DE_RECIBO = [
    { clave: 'REMUNERATIVO', titulo: 'Remunerativo' },
    { clave: 'NO_REMUNERATIVO', titulo: 'No remunerativo' },
    { clave: 'DESCUENTO', titulo: 'Descuentos' },
  ];
  const TIPOS_DE_CONCEPTO = [
    { clave: 'REMUNERATIVO_FIJO', nombre: 'Remunerativo fijo' },
    { clave: 'HORAS', nombre: 'Horas' },
    { clave: 'NO_REMUNERATIVO', nombre: 'No remunerativo' },
    { clave: 'DESCUENTO_PORCENTAJE', nombre: 'Descuento (%)' },
  ];

  const datos = { conceptos: [], escalas: [], escalaEditada: null };

  const porcentaje = (n) => `${Math.abs(Number(n)).toLocaleString('es-AR', { maximumFractionDigits: 1 })}%`;
  const signo = (n) => (Number(n) > 0 ? '+' : Number(n) < 0 ? '−' : '');
  const horasTexto = (n) => `${Number(n).toLocaleString('es-AR', { maximumFractionDigits: 2 })} h`;
  const diaDeEscala = (iso) => { const [a, m] = iso.split('-'); return `${mayuscula(nombreMes(`${a}-${m}`))} ${a}`; };
  const claseMonto = (n) => (n == null ? 'sin-especificar' : null);
  const montoOGuion = (n) => (n == null ? '—' : pesos(n));
  const mensajeError = (el, error) => { el.textContent = error ? error.message : ''; el.hidden = !error; };

  // --- Mes por mes ---

  /** "Cobrado vs calculado: −$4.400 (−0,4%)"; en rojo si pasa la tolerancia. */
  function diferencia(etiqueta, c) {
    const pct = c.porcentaje == null ? '' : ` (${signo(c.diferencia)}${porcentaje(c.porcentaje)})`;
    const texto = Number(c.diferencia) === 0 ? 'igual' : `${signo(c.diferencia)}${pesos(Math.abs(Number(c.diferencia)))}${pct}`;
    return h('div', { class: c.fuera ? 'empeora' : null }, `${etiqueta}: ${texto}`);
  }

  function filaMes(m) {
    const diferencias = [];
    if (m.cobradoVsCalculado) diferencias.push(diferencia('Cobrado vs calculado', m.cobradoVsCalculado));
    if (m.reciboVsCobrado) diferencias.push(diferencia('Recibo vs cobrado', m.reciboVsCobrado));
    if (m.reciboId != null) {
      diferencias.push(m.reciboSinNadaRaro
        ? h('div', { class: 'mejora con-icono' }, Iconos.svg('circle-check'), 'Recibo sin nada raro')
        : h('div', { class: 'empeora con-icono' }, Iconos.svg('triangle-alert'), `${m.avisos.length === 1 ? '1 cosa rara' : `${m.avisos.length} cosas raras`} en el recibo`));
    } else if (m.cobrado != null) {
      diferencias.push(h('div', { class: 'note' }, 'Recibo: todavía no cargaste el de este mes'));
    }
    const boton = m.calculado != null || m.reciboId != null
      ? h('button', { class: 'mini desplegar', type: 'button', 'aria-expanded': 'false' }, 'Detalle') : null;
    const fila = h('tr', {},
      h('td', {}, mesYAnio(m.mes)),
      h('td', { class: `amt ${claseMonto(m.calculado) || ''}` }, montoOGuion(m.calculado)),
      h('td', { class: `amt ${claseMonto(m.cobrado) || ''}` }, montoOGuion(m.cobrado)),
      h('td', { class: `amt ${claseMonto(m.reciboNeto) || ''}` }, montoOGuion(m.reciboNeto)),
      h('td', {}, ...diferencias),
      h('td', {}, boton));
    if (boton) boton.addEventListener('click', () => desplegar(boton, fila, m));
    return fila;
  }

  async function seguimiento(mes) {
    const filas = await api(`/api/sueldo/meses?hasta=${mes}&meses=12`);
    const conDatos = filas.filter((m) => m.calculado != null || m.cobrado != null || m.reciboId != null).reverse();
    const tbody = $('#sueldo-filas');
    if (!conDatos.length) {
      tbody.replaceChildren(h('tr', {}, h('td', { class: 'mensaje', colspan: 6 },
        'Todavía no hay datos de sueldo. Calculá el mes más abajo, cargá lo que cobraste con /sueldo y mandale el recibo al bot.')));
      return;
    }
    tbody.replaceChildren(...conDatos.map(filaMes));
  }

  /** Abre (o cierra) debajo del mes el cálculo y el recibo. Se piden a la API recién al abrir. */
  async function desplegar(boton, fila, m) {
    const abierta = fila.nextElementSibling;
    if (abierta && abierta.classList.contains('detalle-recibo')) {
      abierta.remove();
      boton.setAttribute('aria-expanded', 'false');
      return;
    }
    boton.disabled = true;
    try {
      const [calculo, recibo] = await Promise.all([
        m.calculado != null ? api(`/api/sueldo/calculo?mes=${m.mes}`) : null,
        m.reciboId != null ? api(`/api/recibos/${m.reciboId}`) : null,
      ]);
      fila.after(h('tr', { class: 'detalle-recibo' }, h('td', { colspan: 6 },
        h('div', { class: 'sueldo-detalle' }, calculo ? bloqueCalculo(calculo) : null, recibo ? bloqueRecibo(recibo) : null))));
      boton.setAttribute('aria-expanded', 'true');
    } catch (error) {
      mostrarAviso(`No pude cargar el detalle: ${error.message}`);
    } finally {
      boton.disabled = false;
    }
  }

  function totales(r) {
    return h('ul', {},
      h('li', { class: 'total' }, h('span', {}, 'Bruto'), h('span', { class: 'money' }, pesos(r.bruto))),
      h('li', {}, h('span', {}, 'Descuentos'), h('span', { class: 'money' }, '−' + pesos(r.descuentos))),
      r.netoHaberes != null ? h('li', {}, h('span', {}, 'Neto de haberes'), h('span', { class: 'money' }, pesos(r.netoHaberes))) : null,
      r.noRemunerativo != null ? h('li', {}, h('span', {}, 'No remunerativo'), h('span', { class: 'money' }, pesos(r.noRemunerativo))) : null,
      h('li', { class: 'total' }, h('span', {}, r.netoTotal != null ? 'Neto total' : 'Neto'),
        h('span', { class: 'money' }, pesos(r.netoTotal != null ? r.netoTotal : r.neto))));
  }

  /** El cálculo: un renglón por concepto (los de importe cero no se muestran) y los totales. */
  function bloqueCalculo(c) {
    const lineas = c.lineas.filter((l) => Number(l.importe) !== 0);
    return h('div', { class: 'recibo' },
      h('div', { class: 'sub' }, `Cálculo de ${mesYAnio(c.mes)}${c.escalaDesde ? ` · escala desde ${diaDeEscala(c.escalaDesde)}` : ''}`),
      h('ul', {}, lineas.map((l) => h('li', {},
        h('span', {}, l.nombre + (l.cantidad != null ? ` (${horasTexto(l.cantidad)})` : '') + (l.ajustado ? ' *' : '')),
        h('span', { class: 'money' }, (l.tipo === 'DESCUENTO_PORCENTAJE' ? '−' : '') + pesos(l.importe))))),
      totales(c),
      lineas.some((l) => l.ajustado) ? h('p', { class: 'note' }, '* ajustado a mano ese mes') : null);
  }

  /** El recibo con lo que encontró el control: lo raro o que coincide. */
  function bloqueRecibo(d) {
    const bloques = GRUPOS_DE_RECIBO.map((g) => ({ ...g, conceptos: d.conceptos.filter((c) => c.grupo === g.clave) }))
      .filter((g) => g.conceptos.length);
    return h('div', { class: 'recibo' },
      h('div', { class: 'sub' }, `${d.tipoNombre} ${mesYAnio(d.periodo)} · ${d.empleador}`),
      ...bloques.flatMap((g) => [
        h('h3', {}, g.titulo),
        h('ul', {}, g.conceptos.map((c) => h('li', {},
          h('span', {}, c.descripcion),
          h('span', { class: 'money' }, (g.clave === 'DESCUENTO' ? '−' : '') + pesos(c.importe))))),
      ]),
      totales({ bruto: d.bruto, descuentos: d.descuentos, neto: d.neto }),
      d.hallazgos.length
        ? h('div', {}, h('h3', {}, 'Para revisar'), h('ul', { class: 'raro' }, d.hallazgos.map((t) => h('li', { class: 'con-icono' }, Iconos.svg('triangle-alert'), t))))
        : h('p', { class: 'mejora con-icono' }, Iconos.svg('circle-check'), d.conCalculo ? 'El recibo coincide con tu cálculo' : 'No encontré nada raro (sin cálculo ese mes para comparar los conceptos)'),
      vinculador(d),
      d.conArchivo
        ? h('a', { class: 'ver-todos', href: `/api/recibos/${d.id}/archivo`, target: '_blank', rel: 'noopener' }, 'Ver recibo')
        : null);
  }

  // --- Vincular el recibo con el sueldo cobrado (para corregir lo que se vinculó solo) ---

  const mesMas = (mes, n) => {
    const [a, m] = mes.split('-').map(Number);
    const f = new Date(Date.UTC(a, m - 1 + n, 1));
    return `${f.getUTCFullYear()}-${String(f.getUTCMonth() + 1).padStart(2, '0')}`;
  };

  const textoCobro = (c) => (c
    ? `Sueldo cobrado vinculado: ${pesos(c.monto)} del ${c.fecha.slice(8, 10)}/${c.fecha.slice(5, 7)}${c.aMano ? ' (a mano)' : ' (por período)'}`
    : 'Todavía no hay un sueldo cobrado vinculado a este recibo.');

  function vinculador(d) {
    const info = h('p', { class: 'note' }, textoCobro(d.cobro));
    const caja = h('div', { class: 'vinculo' }, info);
    const abrir = h('button', { type: 'button', class: 'mini' }, 'Vincular a mano');
    abrir.addEventListener('click', async () => {
      abrir.disabled = true;
      try {
        const meses = await Promise.all([0, 1, 2].map((n) => api(`/api/ingresos?mes=${mesMas(d.periodo, n)}`)));
        const candidatos = meses.flatMap((r) => r.ingresos).filter((i) => i.clase === 'SUELDO' || i.clase === 'AGUINALDO');
        const selector = h('select', { 'aria-label': 'Sueldo cobrado' },
          h('option', { value: '' }, 'Buscar solo por período'),
          ...candidatos.map((i) => h('option', { value: i.id, selected: d.cobro && d.cobro.movimientoId === i.id },
            `${i.claseNombre} del ${fechaCorta(i.fecha)} · ${pesos(i.monto)}`)));
        const guardar = h('button', { type: 'button', class: 'mini primary' }, 'Guardar vínculo');
        guardar.addEventListener('click', async () => {
          guardar.disabled = true;
          try {
            const r = await api(`/api/recibos/${d.id}/vincular`, {
              method: 'POST', headers: JSON_HEADERS,
              body: JSON.stringify({ movimientoId: selector.value ? Number(selector.value) : null }),
            });
            info.textContent = textoCobro(r.cobro);
            Iconos.toast('Recibo vinculado');
            seguimiento(estado.mes);
          } catch (e) {
            mostrarAviso(`No pude vincular el recibo: ${e.message}`);
          } finally {
            guardar.disabled = false;
          }
        });
        abrir.replaceWith(selector, guardar);
      } catch (e) {
        mostrarAviso(`No pude cargar los sueldos cobrados: ${e.message}`);
        abrir.disabled = false;
      }
    });
    caja.append(abrir);
    return caja;
  }

  // --- Calculadora ---

  const horasActivas = () => datos.conceptos.filter((c) => c.activo && c.tipo === 'HORAS');

  function camposDeHoras(guardado) {
    const horas = new Map((guardado ? guardado.lineas : []).filter((l) => l.cantidad != null).map((l) => [l.conceptoId, l.cantidad]));
    $('#calc-horas').replaceChildren(...horasActivas().map((c) => h('label', { class: 'calc-campo' },
      h('span', {}, `${c.nombre} (horas)`),
      h('input', { type: 'text', inputmode: 'decimal', 'data-concepto': c.id, value: horas.has(c.id) ? String(horas.get(c.id)).replace('.', ',') : '', placeholder: '0', autocomplete: 'off' }))));
  }

  const pedidoDeHoras = () => {
    const horas = {};
    for (const input of document.querySelectorAll('#calc-horas input')) {
      const valor = input.value.trim() === '' ? 0 : numeroArg(input.value);
      if (Number.isNaN(valor)) throw new Error('Las horas tienen que ser números');
      horas[input.dataset.concepto] = valor;
    }
    return { mes: estado.mes, horas, ajustes: {} };
  };

  function mostrarResultado(c) {
    $('#calc-resultado').replaceChildren(bloqueCalculo(c));
    $('#calc-nota').textContent = c.guardado ? 'Cálculo guardado' : 'Cuenta en vivo (todavía sin guardar)';
  }

  async function calcularEnVivo() {
    const error = $('#calc-error');
    try {
      mostrarResultado(await api('/api/sueldo/calcular', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(pedidoDeHoras()) }));
      mensajeError(error, null);
    } catch (e) {
      $('#calc-resultado').replaceChildren();
      mensajeError(error, e);
    }
  }

  async function guardarCalculo(evento) {
    evento.preventDefault();
    const boton = $('#calc-guardar');
    boton.disabled = true;
    try {
      const guardado = await api('/api/sueldo/calculo', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(pedidoDeHoras()) });
      mostrarResultado(guardado);
      mensajeError($('#calc-error'), null);
      Iconos.toast('Cálculo guardado');
      seguimiento(estado.mes);
    } catch (e) {
      mensajeError($('#calc-error'), e);
    } finally {
      boton.disabled = false;
    }
  }

  async function calculadora(mes) {
    datos.conceptos = await api('/api/sueldo/conceptos');
    const guardado = await api(`/api/sueldo/calculo?mes=${mes}`);
    camposDeHoras(guardado.existe ? guardado : null);
    if (guardado.existe) mostrarResultado(guardado); else await calcularEnVivo();
    $('#calc-nota').textContent = `${mesYAnio(mes)} · ${$('#calc-nota').textContent}`;
    await configuracion();
  }

  // --- Escalas y conceptos ---

  async function configuracion() {
    datos.escalas = await api('/api/sueldo/escalas');
    dibujarEscalas();
    dibujarConceptos();
  }

  const conceptosConValor = () => datos.conceptos.filter((c) => c.activo && c.tipo !== 'HORAS');

  function dibujarEscalas() {
    const contenedor = $('#calc-escalas');
    const e = datos.escalaEditada || datos.escalas[0] || null;
    const nueva = e && e.id == null;
    const selector = h('select', { 'aria-label': 'Escala' }, ...datos.escalas.map((x) =>
      h('option', { value: x.id, selected: e && x.id === e.id }, `Desde ${diaDeEscala(x.vigenteDesde)}`)),
      nueva ? h('option', { value: '', selected: true }, 'Escala nueva') : null);
    selector.addEventListener('change', () => {
      datos.escalaEditada = datos.escalas.find((x) => String(x.id) === selector.value) || null;
      dibujarEscalas();
    });
    const form = h('form', { class: 'escala-form', novalidate: true },
      h('div', { class: 'btns' }, selector,
        h('button', { type: 'button', class: 'mini', onclick: () => escalaNueva(e) }, 'Nueva escala (copia la última)')),
      h('label', { class: 'calc-campo' }, h('span', {}, 'Rige desde (mes)'),
        h('input', { type: 'month', 'data-campo': 'desde', value: e ? e.vigenteDesde.slice(0, 7) : '', required: true })),
      h('label', { class: 'calc-campo' }, h('span', {}, 'Valor de la hora normal'),
        h('input', { type: 'text', inputmode: 'decimal', 'data-campo': 'hora', value: e ? String(e.valorHora).replace('.', ',') : '', autocomplete: 'off' })),
      ...conceptosConValor().map((c) => h('label', { class: 'calc-campo' },
        h('span', {}, c.tipo === 'DESCUENTO_PORCENTAJE' ? `${c.nombre} (%)` : c.nombre),
        h('input', { type: 'text', inputmode: 'decimal', 'data-valor': c.id, autocomplete: 'off',
          value: e && e.valores[c.id] != null ? String(e.valores[c.id]).replace('.', ',') : '' }))),
      h('p', { class: 'error', role: 'alert', hidden: true, id: 'escala-error' }),
      h('div', { class: 'btns' }, h('button', { type: 'submit', class: 'primary' }, nueva || !e ? 'Crear escala' : 'Guardar cambios')),
      h('p', { class: 'note' }, 'Los cálculos ya guardados no cambian al editar una escala.'));
    form.addEventListener('submit', (ev) => guardarEscala(ev, e));
    contenedor.replaceChildren(h('h3', {}, 'Escalas (paritarias)'), form);
  }

  /** Una escala nueva con los valores de la última, para editar solo lo que cambió. */
  function escalaNueva(base) {
    const origen = datos.escalas[0] || base;
    datos.escalaEditada = { id: null, vigenteDesde: '', valorHora: origen ? origen.valorHora : '', valores: origen ? { ...origen.valores } : {} };
    dibujarEscalas();
  }

  async function guardarEscala(evento, escala) {
    evento.preventDefault();
    const error = $('#escala-error');
    try {
      const valores = {};
      for (const input of document.querySelectorAll('#calc-escalas [data-valor]')) {
        if (input.value.trim() === '') continue;
        const n = numeroArg(input.value);
        if (Number.isNaN(n)) throw new Error('Los montos y porcentajes tienen que ser números');
        valores[input.dataset.valor] = n;
      }
      const hora = numeroArg($('#calc-escalas [data-campo="hora"]').value || '0');
      const desde = $('#calc-escalas [data-campo="desde"]').value;
      if (!desde) throw new Error('Elegí desde qué mes rige la escala');
      const cuerpo = JSON.stringify({ vigenteDesde: `${desde}-01`, valorHora: hora, valores });
      const guardada = escala && escala.id != null
        ? await api(`/api/sueldo/escalas/${escala.id}`, { method: 'PUT', headers: JSON_HEADERS, body: cuerpo })
        : await api('/api/sueldo/escalas', { method: 'POST', headers: JSON_HEADERS, body: cuerpo });
      datos.escalaEditada = null;
      mensajeError(error, null);
      Iconos.toast('Escala guardada');
      await configuracion();
      datos.escalaEditada = datos.escalas.find((x) => x.id === guardada.id) || null;
      dibujarEscalas();
      await calcularEnVivo();
    } catch (e) {
      mensajeError(error, e);
    }
  }

  function dibujarConceptos() {
    const filas = datos.conceptos.map((c) => filaConcepto(c));
    $('#calc-conceptos').replaceChildren(
      h('h3', {}, 'Conceptos'),
      h('p', { class: 'note' }, 'Las horas llevan su factor como fracción: 3/2 es 1,5; 8/60 son 8 minutos por hora nocturna.'),
      h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, ...['Orden', 'Nombre', 'Tipo', 'Factor', 'Activo', ''].map((t) => h('th', {}, t)))),
        h('tbody', {}, ...filas, filaConcepto(null)))));
  }

  function filaConcepto(c) {
    const nuevo = c == null;
    const orden = h('input', { type: 'number', min: 1, 'aria-label': 'Orden', value: nuevo ? datos.conceptos.length + 1 : c.orden, class: 'corto' });
    const nombre = h('input', { type: 'text', maxlength: 100, 'aria-label': 'Nombre', value: nuevo ? '' : c.nombre, placeholder: nuevo ? 'Concepto nuevo' : null });
    const tipo = h('select', { 'aria-label': 'Tipo' }, ...TIPOS_DE_CONCEPTO.map((t) => h('option', { value: t.clave, selected: !nuevo && c.tipo === t.clave }, t.nombre)));
    const num = h('input', { type: 'number', min: 1, 'aria-label': 'Numerador', value: !nuevo && c.factorNum != null ? c.factorNum : '', class: 'corto' });
    const den = h('input', { type: 'number', min: 1, 'aria-label': 'Denominador', value: !nuevo && c.factorDen != null ? c.factorDen : '', class: 'corto' });
    const activo = h('input', { type: 'checkbox', 'aria-label': 'Activo', checked: nuevo || c.activo });
    const factor = h('span', { class: 'factor' }, num, ' / ', den);
    const ajustarFactor = () => { factor.hidden = tipo.value !== 'HORAS'; };
    tipo.addEventListener('change', ajustarFactor);
    ajustarFactor();
    const boton = h('button', { type: 'button', class: 'mini' }, nuevo ? 'Agregar' : 'Guardar');
    boton.addEventListener('click', async () => {
      boton.disabled = true;
      try {
        const cuerpo = JSON.stringify({
          nombre: nombre.value, tipo: tipo.value, orden: Number(orden.value) || null, activo: activo.checked,
          factorNum: tipo.value === 'HORAS' ? Number(num.value) || null : null,
          factorDen: tipo.value === 'HORAS' ? Number(den.value) || null : null,
        });
        await (nuevo ? api('/api/sueldo/conceptos', { method: 'POST', headers: JSON_HEADERS, body: cuerpo })
          : api(`/api/sueldo/conceptos/${c.id}`, { method: 'PUT', headers: JSON_HEADERS, body: cuerpo }));
        await calculadora(estado.mes);
        Iconos.toast('Concepto guardado');
      } catch (e) {
        mostrarAviso(`No pude guardar el concepto: ${e.message}`);
      } finally {
        boton.disabled = false;
      }
    });
    return h('tr', {}, h('td', {}, orden), h('td', {}, nombre), h('td', {}, tipo), h('td', {}, factor), h('td', {}, activo), h('td', {}, boton));
  }

  // --- Conexión con el dashboard ---

  function conectar() {
    if (conectar.hecho) return;
    conectar.hecho = true;
    $('#form-calculo').addEventListener('submit', guardarCalculo);
    let espera;
    $('#calc-horas').addEventListener('input', () => { clearTimeout(espera); espera = setTimeout(calcularEnVivo, 250); });
  }

  /** La sección Ingresos cargó un mes: se pide todo lo del sueldo de ese mes. */
  async function alCargarIngresos(mes) {
    conectar();
    datos.escalaEditada = null;
    await Promise.all([seguimiento(mes), calculadora(mes)]);
  }

  window.Gastito.alCargarIngresos = [alCargarIngresos];
})();
