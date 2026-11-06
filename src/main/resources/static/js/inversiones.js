'use strict';

/*
 * Las inversiones (ahorros): el total invertido hoy, lo que se puso y el rendimiento, una fila por cuenta y posición
 * con "Actualizar valor", el gráfico del valor contra lo aportado, las reservas para metas, el historial de aportes y
 * retiros y las valuaciones. También la línea "Ahorros e inversiones" del Resumen. No calcula nada: los totales, los
 * rendimientos y los porcentajes llegan hechos de la API.
 */
(() => {
  const { h, api, pesos, fechaCorta, toast, numeroArg } = window.Gastito;
  const { Iconos } = window;
  const $ = (selector) => document.querySelector(selector);
  const JSON_HEADERS = { 'Content-Type': 'application/json' };
  const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });
  const TIPOS = { APORTE: 'Aporte', RETIRO: 'Retiro', SALDO_INICIAL: 'Saldo inicial', COMPRA: 'Compra', VENTA: 'Venta' };
  const ORIGENES = { MANUAL: 'Cargado', IGUAL: 'Igual que antes', AJUSTE: 'Ajuste' };
  const RECORDATORIOS = [[7, 'Cada 7 días'], [14, 'Cada 14 días'], [30, 'Cada 30 días'], [0, 'No preguntar']];

  let datos = null;
  let catalogo = null;
  let grafico = null;

  /** "+$20.000 (+2,35 %)" o "−$30.000 (−3,33 %)": una ganancia o una pérdida, con su color. */
  function rendimiento(pesosRend, porcentaje) {
    const n = Number(pesosRend);
    if (n === 0) return h('span', {}, '$0');
    const signo = n > 0 ? '+' : '−';
    const pct = porcentaje == null ? '' : ` (${signo}${PORCENTAJE.format(Math.abs(Number(porcentaje)))} %)`;
    return h('span', { class: n > 0 ? 'gana' : 'pierde' }, `${signo}${pesos(Math.abs(n))}${pct}`);
  }

  const hace = (dias) => (dias == null ? '' : dias <= 0 ? 'hoy' : dias === 1 ? 'ayer' : `hace ${dias} días`);
  const hoyIso = () => new Date().toLocaleDateString('sv-SE');
  const mensajeError = (error) => { const el = $('#inv-error'); el.textContent = error ? error.message : ''; el.hidden = !error; };

  async function enviar(ruta, cuerpo, exito, metodo = 'POST') {
    try {
      await api(ruta, { method: metodo, headers: JSON_HEADERS, body: cuerpo == null ? undefined : JSON.stringify(cuerpo) });
      toast(exito);
      cerrarEditor();
      await vista();
    } catch (err) {
      const el = $('#inv-editor .error');
      if (el && !$('#inv-editor').hidden) { el.textContent = err.message; el.hidden = false; } else mensajeError(err);
    }
  }

  // --- Resumen: la línea debajo del disponible ---

  async function enResumen() {
    const linea = $('#r-ahorros');
    const mes = window.Gastito.estado.mes;
    const a = await api(`/api/inversiones/resumen?mes=${mes}`);
    if (!a.hay) { linea.hidden = true; return; }
    const partes = [Iconos.svg('trending-up'), 'Ahorros e inversiones: ', h('b', { class: 'money' }, pesos(a.invertido))];
    if (a.totalConDisponible != null) {
      partes.push(' · total con lo disponible: ', h('b', { class: 'money' }, pesos(a.totalConDisponible)));
      if (Number(a.movidoEnElMes) !== 0) {
        partes.push(h('span', { class: 'note' }, ` (sin contar dos veces los ${pesos(Math.abs(Number(a.movidoEnElMes)))} que ${Number(a.movidoEnElMes) > 0 ? 'pasaste a' : 'sacaste de'} las inversiones este mes)`));
      }
    }
    partes.push(' ', h('a', { href: '#inversiones', 'data-view': 'inversiones', class: 'ver-todos' }, 'Ver'));
    linea.replaceChildren(...partes);
    linea.hidden = false;
  }

  // --- La pestaña ---

  async function vista() {
    mensajeError(null);
    [datos, catalogo] = await Promise.all([api('/api/inversiones'), catalogo ? Promise.resolve(catalogo) : api('/api/catalogo')]);
    const vacio = datos.cuentas.length === 0;
    $('#inv-vacio').hidden = !vacio;
    $('#inv-contenido').hidden = vacio;
    $('#inv-operacion').hidden = vacio;
    $('#inv-total').textContent = pesos(datos.valor);
    $('#inv-actualizado').textContent = datos.actualizado ? `Actualizado ${hace(datos.diasSinActualizar)}` : '';
    $('#inv-aportado').textContent = pesos(datos.aportado);
    $('#inv-rendimiento').replaceChildren(rendimiento(datos.rendimiento, datos.porcentaje));
    $('#inv-rendimiento-nota').textContent = Number(datos.rendimiento) < 0 ? 'Pérdida: no es un gasto' : 'Ganancia: no es un ingreso';
    $('#inv-reservado').textContent = pesos(datos.reservado);
    $('#inv-reservado-nota').textContent = datos.cuentas.flatMap((c) => c.reservas).map((r) => r.meta).join(' · ') || 'Nada reservado';
    if (vacio) return;
    tenencias();
    graficoValor();
    reservas();
    historial();
    valuaciones();
    cuentas();
  }

  function tenencias() {
    const filas = [];
    for (const c of datos.cuentas) {
      filas.push(h('tr', { class: 'fila-cuenta' },
        h('td', {}, h('b', {}, c.nombre)),
        h('td', { class: 'num money' }, pesos(c.aportado)),
        h('td', { class: 'num money' }, h('b', {}, pesos(c.valor))),
        h('td', { class: 'num' }, rendimiento(c.rendimiento, c.porcentaje)),
        h('td', {}, c.actualizado ? `${fechaCorta(c.actualizado)} (${hace(c.diasSinActualizar)})` : '—'),
        h('td', { class: 'acciones' }, h('button', { type: 'button', class: 'mini', onclick: () => editorValor(c, null) },
          Iconos.svg('pencil'), 'Actualizar valor'))));
      if (c.tenencias.some((t) => t.posicionId != null)) {
        for (const t of c.tenencias) {
          filas.push(h('tr', { class: 'fila-posicion' },
            h('td', {}, '↳ ', t.nombre),
            h('td', { class: 'num money' }, pesos(t.aportado)),
            h('td', { class: 'num money' }, pesos(t.valor), t.sinValuar ? h('span', { class: 'chip estimado', title: 'Nunca cargaste su valor: vale lo que pusiste' }, 'sin valuar') : null),
            h('td', { class: 'num' }, rendimiento(t.rendimiento, t.porcentaje)),
            h('td', {}, t.ultimaValuacion ? fechaCorta(t.ultimaValuacion) : '—'),
            h('td', { class: 'acciones' }, t.posicionId == null ? null
              : h('button', { type: 'button', class: 'mini', onclick: () => editorValor(c, t) }, Iconos.svg('pencil'), 'Actualizar valor'))));
        }
      }
    }
    $('#inv-tenencias').replaceChildren(...filas);
  }

  function color(variable) {
    return getComputedStyle(document.documentElement).getPropertyValue(variable).trim();
  }

  function graficoValor() {
    if (grafico) grafico.destroy();
    const puntos = datos.serie;
    grafico = new window.Chart($('#inv-grafico'), {
      type: 'line',
      data: {
        labels: puntos.map((p) => fechaCorta(p.fecha)),
        datasets: [
          { label: 'Vale', data: puntos.map((p) => Number(p.valor)), borderColor: color('--accent'), backgroundColor: color('--accent'), tension: 0.2, pointRadius: 3, stepped: false },
          { label: 'Pusiste', data: puntos.map((p) => Number(p.aportado)), borderColor: color('--linea-promedio'), borderDash: [5, 4], pointRadius: 0, stepped: 'before' },
        ],
      },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { labels: { color: color('--muted'), boxWidth: 12 } },
          tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${pesos(c.parsed.y)}` } },
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: color('--muted'), maxRotation: 0, autoSkipPadding: 8 } },
          y: { grid: { color: color('--row') }, ticks: { color: color('--muted'), callback: (v) => pesos(v), maxTicksLimit: 5 } },
        },
      },
    });
  }

  function reservas() {
    const items = [];
    for (const c of datos.cuentas) {
      for (const r of c.reservas) {
        items.push(h('div', { class: 'pendiente' }, Iconos.svg('tag'),
          h('div', { class: 'crece' }, h('b', {}, r.meta), ` · ${pesos(r.monto)} de ${c.nombre}`),
          h('button', { type: 'button', class: 'mini', onclick: () => {
            if (confirm(`¿Liberar la reserva para ${r.meta}? La plata sigue invertida.`)) enviar(`/api/inversiones/reservas/${r.id}/liberar`, null, 'Reserva liberada');
          } }, 'Liberar')));
      }
      if (c.reservas.length) items.push(h('p', { class: 'note' }, `${c.nombre}: libre ${pesos(c.libre)} de ${pesos(c.valor)}`));
    }
    $('#inv-reservas').replaceChildren(...(items.length ? items : [h('p', { class: 'note' }, 'No tenés nada reservado. Decíselo al bot ("reservá 300.000 de Cocos para Bariloche") o tocá Reservar.')]));
  }

  function historial() {
    const filas = datos.historial.map((o) => {
      const [tipo, id] = o.clave.split(':');
      return h('tr', {},
        h('td', { class: 'date' }, fechaCorta(o.fecha)),
        h('td', {}, h('span', { class: `chip ${o.tipo === 'APORTE' || o.tipo === 'SALDO_INICIAL' ? 'inversion' : 'extra'}` }, TIPOS[o.tipo] || o.tipo)),
        h('td', {}, o.cuenta, o.posicion ? ` · ${o.posicion}` : ''),
        h('td', {}, o.otraCuenta || (o.tipo === 'COMPRA' ? 'Desde lo sin invertir' : o.tipo === 'VENTA' ? 'A lo sin invertir' : '—')),
        h('td', { class: 'num money' }, pesos(o.monto)),
        h('td', { class: 'acciones' }, h('button', { type: 'button', class: 'mini peligro', onclick: () => {
          if (!confirm(`¿Borrar ${TIPOS[o.tipo].toLowerCase()} de ${pesos(o.monto)}?`)) return;
          enviar(tipo === 'm' ? `/api/inversiones/movimientos/${id}` : `/api/inversiones/operaciones/${id}`, null, 'Borrado', 'DELETE');
        } }, Iconos.svg('x'), 'Borrar')));
    });
    $('#inv-historial').replaceChildren(...(filas.length ? filas
      : [h('tr', {}, h('td', { colspan: 6, class: 'note' }, 'Todavía no hay aportes ni retiros.'))]));
  }

  function valuaciones() {
    const filas = datos.valuaciones.map((v) => h('tr', {},
      h('td', { class: 'date' }, fechaCorta(v.fecha)),
      h('td', {}, v.cuenta),
      h('td', {}, v.posicion || '—'),
      h('td', { class: 'num money' }, pesos(v.valor)),
      h('td', {}, ORIGENES[v.origen] || v.origen),
      h('td', { class: 'acciones' }, v.origen === 'AJUSTE' ? null : h('button', { type: 'button', class: 'mini peligro', onclick: () => {
        if (confirm(`¿Borrar el valor de ${pesos(v.valor)} del ${fechaCorta(v.fecha)}?`)) enviar(`/api/inversiones/valuaciones/${v.id}`, null, 'Valor borrado', 'DELETE');
      } }, Iconos.svg('x'), 'Borrar'))));
    $('#inv-valuaciones').replaceChildren(...(filas.length ? filas
      : [h('tr', {}, h('td', { colspan: 6, class: 'note' }, 'Todavía no cargaste ningún valor: cada inversión vale lo que pusiste.'))]));
  }

  function cuentas() {
    $('#inv-cuentas').replaceChildren(...datos.cuentas.map((c) => {
      const recordatorio = h('select', { 'aria-label': `Recordatorio de ${c.nombre}` },
        ...RECORDATORIOS.map(([dias, texto]) => h('option', { value: dias, selected: (c.recordatorioDias || 0) === dias }, texto)));
      if (c.recordatorioDias && !RECORDATORIOS.some(([d]) => d === c.recordatorioDias)) {
        recordatorio.prepend(h('option', { value: c.recordatorioDias, selected: true }, `Cada ${c.recordatorioDias} días`));
      }
      recordatorio.addEventListener('change', () => enviar(`/api/inversiones/cuentas/${c.id}`,
        { nombre: c.nombre, alias: c.alias, recordatorioDias: Number(recordatorio.value) }, 'Recordatorio guardado', 'PUT'));
      return h('div', { class: 'pendiente' }, Iconos.svg('landmark'),
        h('div', { class: 'crece' }, h('b', {}, c.nombre), c.alias ? h('span', { class: 'note' }, ` · alias: ${c.alias}`) : null),
        recordatorio,
        h('button', { type: 'button', class: 'mini', onclick: () => editorSaldo(c) }, 'Saldo inicial'),
        h('button', { type: 'button', class: 'mini', onclick: () => editorPosicion(c) }, 'Agregar posición'),
        h('button', { type: 'button', class: 'mini', onclick: () => editorCuenta(c) }, Iconos.svg('pencil'), 'Editar'),
        h('button', { type: 'button', class: 'mini peligro', onclick: () => {
          if (confirm(`¿Dar de baja ${c.nombre}? Deja de sumar; el historial queda.`)) enviar(`/api/inversiones/cuentas/${c.id}/baja`, null, `${c.nombre} dada de baja`);
        } }, 'Dar de baja'));
    }));
  }

  // --- Editor: un formulario a la vez, arriba ---

  function cerrarEditor() { $('#inv-editor').hidden = true; $('#inv-editor').replaceChildren(); }

  function abrirEditor(titulo, nota, campos, alGuardar, textoBoton = 'Guardar') {
    const error = h('p', { class: 'error', role: 'alert', hidden: true });
    const form = h('form', { class: 'form-linea', novalidate: true }, ...campos,
      h('button', { type: 'submit', class: 'primary' }, textoBoton),
      h('button', { type: 'button', onclick: cerrarEditor }, 'Cancelar'));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      error.hidden = true;
      try { alGuardar(form.elements); } catch (err) { error.textContent = err.message; error.hidden = false; }
    });
    const editor = $('#inv-editor');
    editor.replaceChildren(...[h('h2', {}, titulo), nota ? h('p', { class: 'note' }, nota) : null, form, error].filter(Boolean));
    editor.hidden = false;
    editor.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    const primero = form.querySelector('input:not([type=hidden]), select');
    if (primero) primero.focus();
  }

  const campo = (texto, input) => h('label', {}, texto, input);
  const inputMonto = (name, valor = '') => h('input', { type: 'text', inputmode: 'decimal', name, required: true, placeholder: 'Ej: 850.000', value: valor, autocomplete: 'off' });
  const inputFecha = () => h('input', { type: 'date', name: 'fecha', value: hoyIso(), max: hoyIso(), required: true });

  function monto(valor) {
    const n = numeroArg(valor);
    if (!Number.isFinite(n) || n <= 0) throw new Error('Escribí el monto, por ejemplo 850.000');
    return n;
  }

  function selectCuenta(name = 'cuentaId', alCambiar = null) {
    const s = h('select', { name }, ...datos.cuentas.map((c) => h('option', { value: c.id }, c.nombre)));
    if (alCambiar) s.addEventListener('change', alCambiar);
    return s;
  }

  function posicionesDe(cuentaId, conBolsa) {
    const c = datos.cuentas.find((x) => x.id === Number(cuentaId));
    const opciones = (c ? c.tenencias.filter((t) => t.posicionId != null) : [])
      .map((t) => h('option', { value: t.posicionId }, t.nombre));
    return conBolsa ? [h('option', { value: '' }, c && opciones.length ? 'Sin invertir (la bolsa)' : 'Toda la cuenta'), ...opciones] : opciones;
  }

  function editorValor(c, t) {
    const nombre = t ? t.nombre : c.nombre;
    const nota = t ? `Lo que vale hoy ${t.nombre}. Pusiste ${pesos(t.aportado)}.`
      : c.tenencias.some((x) => x.posicionId != null)
        ? `El total de ${c.nombre}: lo que no explican las posiciones queda como sin invertir. Última: ${pesos(c.valor)}.`
        : `Última: ${pesos(c.valor)}${c.actualizado ? ` (${hace(c.diasSinActualizar)})` : ''}.`;
    abrirEditor(`¿Cuánto vale hoy ${nombre}?`, nota, [campo('Vale', inputMonto('valor')), campo('Fecha', inputFecha())],
      (e) => enviar('/api/inversiones/valuaciones', { cuentaId: c.id, posicionId: t ? t.posicionId : null, monto: monto(e.valor.value), fecha: e.fecha.value }, 'Valor guardado'));
  }

  function editorSaldo(c) {
    const posicion = h('select', { name: 'posicionId' }, ...posicionesDe(c.id, true));
    abrirEditor(`Saldo inicial de ${c.nombre}`, 'La plata que ya tenías invertida: es ahorro previo, no cuenta como ingreso. Si ya había uno, lo reemplaza.',
      [campo('Dónde', posicion), campo('Monto', inputMonto('monto')), campo('Al', inputFecha())],
      (e) => enviar('/api/inversiones/saldo-inicial', { cuentaId: c.id, posicionId: e.posicionId.value ? Number(e.posicionId.value) : null, monto: monto(e.monto.value), fecha: e.fecha.value }, 'Saldo inicial guardado'));
  }

  function editorPosicion(c) {
    abrirEditor(`Nueva posición en ${c.nombre}`, 'Con nombre libre: "CEDEAR SPY", "FCI money market", "Acción YPF".',
      [campo('Nombre', h('input', { name: 'nombre', required: true, autocomplete: 'off', maxlength: 100 }))],
      (e) => {
        if (!e.nombre.value.trim()) throw new Error('Poné el nombre de la posición');
        enviar(`/api/inversiones/cuentas/${c.id}/posiciones`, { nombre: e.nombre.value }, 'Posición agregada');
      });
  }

  function editorCuenta(c) {
    abrirEditor(c ? `Editar ${c.nombre}` : 'Nueva cuenta de inversión', c ? null : 'Por ejemplo Cocos. Después cargás el saldo inicial y, si querés, sus posiciones.',
      [campo('Nombre', h('input', { name: 'nombre', required: true, autocomplete: 'off', maxlength: 50, value: c ? c.nombre : '' })),
        campo('Alias (separados por coma)', h('input', { name: 'alias', autocomplete: 'off', value: c && c.alias ? c.alias : '', placeholder: 'cocos capital' })),
        campo('Preguntarme cuánto vale', h('select', { name: 'recordatorio' }, ...RECORDATORIOS.map(([dias, texto]) =>
          h('option', { value: dias, selected: c ? (c.recordatorioDias || 0) === dias : dias === 7 }, texto))))],
      (e) => {
        if (!e.nombre.value.trim()) throw new Error('Poné el nombre de la cuenta');
        const cuerpo = { nombre: e.nombre.value, alias: e.alias.value || null, recordatorioDias: Number(e.recordatorio.value) };
        enviar(c ? `/api/inversiones/cuentas/${c.id}` : '/api/inversiones/cuentas', cuerpo, 'Cuenta guardada', c ? 'PUT' : 'POST');
      });
  }

  function editorOperacion() {
    const tipo = h('select', { name: 'tipo' },
      h('option', { value: 'APORTE' }, 'Aporte (entra plata)'), h('option', { value: 'RETIRO' }, 'Retiro (sale plata)'),
      h('option', { value: 'COMPRA' }, 'Compra (de lo sin invertir a una posición)'), h('option', { value: 'VENTA' }, 'Venta (de una posición a lo sin invertir)'));
    const posicion = h('select', { name: 'posicionId' });
    const otra = h('select', { name: 'otraCuentaId' }, h('option', { value: '' }, 'Efectivo'),
      ...catalogo.cuentas.map((cu) => h('option', { value: cu.id }, cu.nombre)));
    const otraCampo = campo('Desde', otra);
    const cuenta = selectCuenta('cuentaId', () => refrescar());
    function refrescar() {
      const interna = tipo.value === 'COMPRA' || tipo.value === 'VENTA';
      posicion.replaceChildren(...posicionesDe(cuenta.value, !interna));
      otraCampo.hidden = interna;
      otraCampo.firstChild.textContent = tipo.value === 'RETIRO' ? 'Hacia' : 'Desde';
    }
    tipo.addEventListener('change', refrescar);
    abrirEditor('Aporte, retiro, compra o venta', 'Los aportes y retiros son pases entre tus cuentas: no cuentan como gasto ni como ingreso.',
      [campo('Qué', tipo), campo('Cuenta', cuenta), campo('Posición', posicion), otraCampo, campo('Monto', inputMonto('monto')), campo('Fecha', inputFecha())],
      (e) => {
        const interna = e.tipo.value === 'COMPRA' || e.tipo.value === 'VENTA';
        if (interna && !e.posicionId.value) throw new Error('Elegí la posición (si no tiene, agregala en Tus cuentas de inversión)');
        enviar('/api/inversiones/operaciones', {
          tipo: e.tipo.value, cuentaId: Number(e.cuentaId.value), posicionId: e.posicionId.value ? Number(e.posicionId.value) : null,
          otraCuentaId: !interna && e.otraCuentaId.value ? Number(e.otraCuentaId.value) : null, monto: monto(e.monto.value), fecha: e.fecha.value,
        }, 'Guardado');
      });
    refrescar();
  }

  function editorReserva() {
    abrirEditor('Reservar para una meta', 'Aparta parte del valor de una cuenta: sigue invertido. Si ya había una reserva para esa meta en la cuenta, la reemplaza.',
      [campo('De', selectCuenta()), campo('Para', h('input', { name: 'meta', required: true, autocomplete: 'off', maxlength: 100, placeholder: 'Bariloche' })),
        campo('Monto', inputMonto('monto'))],
      (e) => {
        if (!e.meta.value.trim()) throw new Error('Decí para qué es la reserva');
        enviar('/api/inversiones/reservas', { cuentaId: Number(e.cuentaId.value), meta: e.meta.value, monto: monto(e.monto.value) }, 'Reservado');
      });
  }

  $('#inv-nueva-cuenta').addEventListener('click', () => editorCuenta(null));
  $('#inv-operacion').addEventListener('click', editorOperacion);
  $('#inv-reservar').addEventListener('click', editorReserva);

  window.Gastito.alCargarResumen = [...(window.Gastito.alCargarResumen || []), enResumen];
  window.Gastito.alCargarInversiones = [...(window.Gastito.alCargarInversiones || []), vista];
})();
