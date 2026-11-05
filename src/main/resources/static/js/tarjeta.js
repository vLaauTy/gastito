'use strict';

/*
 * Las tarjetas de crédito, cada una con su ciclo: una pestaña por tarjeta con el resumen cerrado por pagar, el
 * próximo (cuánto llevo), las compras de cada resumen y las cuotas comprometidas; arriba, el total entre todas. Las
 * fechas y el total del resumen se cargan acá, y en "Tus tarjetas" se agregan, editan y dan de baja. Como el resto
 * del dashboard, no calcula nada: los totales, las diferencias y en qué resumen entra cada compra llegan de la API.
 */
(() => {
  const { h, api, pesos, fechaCorta, toast, dolaresCompra, montoEnDolares } = window.Gastito;
  const { Iconos } = window;
  const $ = (selector) => document.querySelector(selector);

  const JSON_HEADERS = { 'Content-Type': 'application/json' };
  const ESTADOS = { ABIERTO: 'Abierto', CERRADO: 'Por pagar', PAGADO: 'Pagado' };

  /** "11/11", o "≈ 14/12" si la fecha es una estimación. */
  const dia = (iso, estimada) => `${estimada ? '≈ ' : ''}${fechaCorta(iso)}`;
  const mensajeError = (el, error) => { el.textContent = error ? error.message : ''; el.hidden = !error; };

  /** "Faltan $50.000 sin cargar", "Cargaste $3.000 de más" o "Coincide con lo cargado". */
  function diferencia(r) {
    if (r.diferencia == null) return null;
    const d = Number(r.diferencia);
    if (d === 0) return h('p', { class: 'down con-icono' }, Iconos.svg('circle-check'), 'Coincide con lo cargado');
    return h('p', { class: 'up con-icono' }, Iconos.svg('triangle-alert'),
      d > 0 ? `El resumen dice ${pesos(r.totalReal)} y cargaste ${pesos(r.cargado)}: faltan ${pesos(d)} sin cargar.`
        : `El resumen dice ${pesos(r.totalReal)} y cargaste ${pesos(r.cargado)}: cargaste ${pesos(-d)} de más.`,
      r.dolaresEstimados ? ' Incluye compras en dólares con los pesos estimados: parte de la diferencia puede venir de ahí (corregilas en Movimientos).' : '');
  }

  /** "Compras en dólares: US$ 20 (≈ $30.000)", o null si el resumen no tiene. */
  function comprasEnDolares(r) {
    if (!(Number(r.dolares) > 0)) return null;
    const sinEstimar = r.dolaresSinEstimar > 0
      ? ` · ${r.dolaresSinEstimar === 1 ? '1 sin estimar' : `${r.dolaresSinEstimar} sin estimar`}` : '';
    return h('p', {}, 'Compras en dólares: ', h('b', { class: 'money' }, dolaresCompra(r.dolares)),
      ` (${r.dolaresEstimados ? '≈' : '='} ${pesos(r.dolaresEnPesos)})${sinEstimar}`);
  }

  const desglose = (r) => `compras ${pesos(r.compras)} + cuotas ${pesos(r.cuotas)}`;

  /** La inicial de la tarjeta en su color. */
  const inicial = (t) => h('span', { class: 'inicial-tarjeta', style: t.color ? `--tarjeta: ${t.color}` : null,
    'aria-hidden': 'true' }, t.inicial || (t.tarjeta || t.nombre || '?').slice(0, 1));

  /** La pestaña elegida (por viewer, en este navegador). */
  const CLAVE_PESTANA = 'gastito.tarjeta';
  const leerPestana = () => { try { return Number(localStorage.getItem(CLAVE_PESTANA)) || null; } catch { return null; } };
  const guardarPestana = (id) => { try { localStorage.setItem(CLAVE_PESTANA, String(id)); } catch { /* sin storage */ } };
  let elegida = leerPestana();

  // --- Resumen (la tarjeta del costado) ---

  async function enResumen() {
    const contenedor = $('#r-tarjeta');
    const { tarjetas } = await api('/api/tarjeta');
    const fila = (icono, ...hijos) => h('div', { class: 'pendiente' }, Iconos.svg(icono), h('div', {}, ...hijos));
    const varias = tarjetas.length > 1;
    const filas = [];
    for (const t of tarjetas) {
      const nombre = varias ? [h('b', {}, t.tarjeta), ' · '] : [];
      if (!t.configurado) {
        filas.push(fila('credit-card', ...nombre, 'Cargá el último cierre y vencimiento en ',
          h('a', { href: '#tarjeta', 'data-view': 'tarjeta', class: 'ver-todos' }, 'Tarjeta'), ' (o decíselo al bot).'));
        continue;
      }
      for (const r of t.porPagar) {
        filas.push(fila('receipt', ...nombre, h('b', {}, 'Resumen cerrado por pagar'), h('br'),
          `Vence el ${dia(r.vencimiento, r.estimadas)} · ${r.totalReal == null ? 'según lo cargado' : 'el resumen dice'} `,
          h('span', { class: 'money' }, pesos(r.aDebitar))));
      }
      const p = t.proximo;
      filas.push(fila('calendar-clock', ...nombre, h('b', {}, 'Próximo resumen'), h('br'),
        `Cierra ${dia(p.cierre, p.estimadas)} · vence ${dia(p.vencimiento, p.estimadas)} · llevás `,
        h('span', { class: 'money' }, pesos(p.cargado)), ` (${desglose(p)})`));
    }
    contenedor.replaceChildren(...(filas.length ? filas
      : [fila('credit-card', 'No tenés ninguna tarjeta de crédito activa.')]));
  }

  // --- La sección Tarjeta ---

  function lineaCompra(l) {
    const detalle = l.cuota != null ? `${l.descripcion || 'Cuota'}` : (l.descripcion || '—');
    return h('tr', {},
      h('td', {}, fechaCorta(l.fecha)),
      h('td', {}, detalle, l.juntada ? h('span', { class: 'chip extra' }, 'juntada: total pagado') : null),
      h('td', {}, Iconos.etiquetaCategoria(l.categoria)),
      h('td', { class: 'num money' }, l.dolares != null ? montoEnDolares(l.dolares, l.monto, l.estimado) : pesos(l.monto),
        l.dolares != null && l.estimado ? h('span', { class: 'chip estimado' }, 'estimado') : null));
  }

  function formTotal(r) {
    const error = h('p', { class: 'error', role: 'alert', hidden: true });
    const input = h('input', { type: 'text', inputmode: 'decimal', name: 'total', placeholder: 'Ej: 250.000', required: true,
      value: r.totalReal == null ? '' : String(r.totalReal).replace('.', ',') });
    const form = h('form', { class: 'form-linea', novalidate: true },
      h('label', {}, 'Total del resumen', input),
      h('button', { type: 'submit', class: 'primary' }, 'Guardar total'));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      mensajeError(error, null);
      const total = window.Gastito.numeroArg(input.value);
      if (!Number.isFinite(total) || total <= 0) { mensajeError(error, new Error('Escribí el total, por ejemplo 250.000')); return; }
      try {
        await api(`/api/tarjeta/${r.id}/total`, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ total }) });
        toast('Total del resumen guardado');
        await vista();
      } catch (err) {
        mensajeError(error, err);
      }
    });
    return [form, error];
  }

  function bloqueResumen(r, titulo) {
    const tabla = r.detalle.length
      ? h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Fecha'), h('th', {}, 'Detalle'), h('th', {}, 'Categoría'), h('th', { class: 'num' }, 'Monto'))),
        h('tbody', {}, ...r.detalle.map(lineaCompra))))
      : Iconos.vacio('inbox', 'Todavía no hay compras en este resumen.');
    return h('section', { class: 'card table-card', 'aria-label': titulo },
      h('div', { class: 'head' },
        h('h2', {}, titulo),
        h('span', {}, h('span', { class: 'chip' }, ESTADOS[r.estado] || r.estado),
          r.estimadas ? h('span', { class: 'chip estimado' }, '≈ estimadas') : null)),
      h('p', { class: 'note' }, `Del ${fechaCorta(r.desde)} al ${dia(r.cierre, r.estimadas)} · vence el ${dia(r.vencimiento, r.estimadas)}`
        + (r.cuentaDebito ? ` · se debita de ${r.cuentaDebito}` : '')),
      h('p', {}, 'Según lo cargado ', h('b', { class: 'money' }, pesos(r.cargado)), ` (${desglose(r)})`),
      comprasEnDolares(r),
      r.totalReal != null ? h('p', {}, 'El resumen dice ', h('b', { class: 'money' }, pesos(r.totalReal))) : null,
      diferencia(r),
      r.estado === 'CERRADO' ? formTotal(r) : null,
      tabla);
  }

  function filaSiguiente(r) {
    return h('div', { class: 'pendiente' }, Iconos.svg('layers'), h('div', {},
      h('b', {}, `Vence ${dia(r.vencimiento, r.estimadas)}`), ' · ', h('span', { class: 'money' }, pesos(r.cuotas)),
      ` en ${r.cantidadCuotas === 1 ? '1 cuota' : `${r.cantidadCuotas} cuotas`}: `,
      r.detalle.map((l) => l.descripcion).join(', ')));
  }

  // --- Dólar tarjeta ---

  async function vistaDolar() {
    const { valor, cargadaEn } = await api('/api/dolar');
    $('#t-dolar-nota').textContent = valor == null
      ? 'Todavía no cargaste ninguna: las compras en dólares quedan sin estimar hasta que la cargues o llegue el resumen.'
      : `Está a ${pesos(valor)} desde el ${fechaCorta(cargadaEn)}. Con ella se estiman los pesos de las compras en dólares hasta que llegue el resumen.`;
    $('#t-form-dolar').elements.valor.value = valor == null ? '' : String(valor).replace('.', ',');
  }

  async function guardarDolar(e) {
    e.preventDefault();
    const error = $('#t-dolar-error');
    mensajeError(error, null);
    const valor = window.Gastito.numeroArg(e.currentTarget.elements.valor.value);
    if (!Number.isFinite(valor) || valor <= 0) { mensajeError(error, new Error('Escribí la cotización, por ejemplo 1.500')); return; }
    try {
      await api('/api/dolar', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ valor }) });
      toast('Dólar tarjeta guardado');
      await vista();
    } catch (err) {
      mensajeError(error, err);
    }
  }

  /** Una pestaña por tarjeta (si hay más de una) y, arriba, el total entre todas. */
  function pestanas(todas) {
    const contenedor = $('#t-pestanas');
    if (todas.tarjetas.length < 2) {
      contenedor.replaceChildren();
      $('#t-entre-todas').hidden = true;
      return;
    }
    contenedor.replaceChildren(...todas.tarjetas.map((t) => {
      const boton = h('button', { type: 'button', class: 'pestana', role: 'tab',
        'aria-selected': String(t.tarjetaId === elegida) }, inicial(t), t.tarjeta);
      boton.addEventListener('click', () => { elegida = t.tarjetaId; guardarPestana(elegida); vista(); });
      return boton;
    }));
    const entre = $('#t-entre-todas');
    entre.hidden = false;
    entre.textContent = `Entre todas: llevás ${pesos(todas.proximos)} en los próximos resúmenes`
      + (Number(todas.porPagar) > 0 ? ` y hay ${pesos(todas.porPagar)} por pagar.` : '.');
  }

  async function vista() {
    await Promise.all([vistaDolar(), vistaTarjetas()]);
    const todas = await api('/api/tarjeta');
    if (!todas.tarjetas.some((t) => t.tarjetaId === elegida)) elegida = todas.tarjetas.length ? todas.tarjetas[0].tarjetaId : null;
    pestanas(todas);
    const panel = todas.tarjetas.find((t) => t.tarjetaId === elegida);
    const nota = $('#t-fechas-nota');
    $('#t-form-fechas').hidden = !panel;
    if (!panel) {
      $('#t-sub').textContent = 'No tenés ninguna tarjeta de crédito activa: agregala abajo, en Tus tarjetas.';
      for (const id of ['#t-cerrado', '#t-proximo', '#t-comprometido']) $(id).textContent = '—';
      for (const id of ['#t-cerrado-nota', '#t-proximo-nota', '#t-comprometido-nota']) $(id).textContent = '';
      nota.textContent = '';
      $('#t-resumenes').replaceChildren();
      $('#t-siguientes').replaceChildren(Iconos.vacio('layers', 'Sin cuotas comprometidas.'));
      return;
    }
    if (!panel.configurado) {
      $('#t-sub').textContent = `${panel.tarjeta} · todavía no cargaste ningún cierre.`;
      for (const id of ['#t-cerrado', '#t-proximo', '#t-comprometido']) $(id).textContent = '—';
      for (const id of ['#t-cerrado-nota', '#t-proximo-nota', '#t-comprometido-nota']) $(id).textContent = '';
      nota.textContent = 'Cargá el último cierre y su vencimiento (los del último resumen que te llegó): desde ahí estimo los próximos.';
      $('#t-resumenes').replaceChildren();
      $('#t-siguientes').replaceChildren(Iconos.vacio('layers', 'Sin cuotas comprometidas.'));
      return;
    }
    const p = panel.proximo;
    $('#t-sub').textContent = `${panel.tarjeta}${panel.cuentaDebito ? ` · se paga desde ${panel.cuentaDebito}` : ''}`;
    const cerrado = panel.porPagar[0];
    $('#t-cerrado').textContent = cerrado ? pesos(cerrado.aDebitar) : '—';
    $('#t-cerrado-nota').textContent = cerrado
      ? `Vence el ${dia(cerrado.vencimiento, cerrado.estimadas)} · ${cerrado.totalReal == null ? 'según lo cargado' : 'según el resumen'}`
      : 'No hay resúmenes sin debitar';
    $('#t-proximo').textContent = pesos(p.cargado);
    $('#t-proximo-nota').textContent = `Cierra ${dia(p.cierre, p.estimadas)} · vence ${dia(p.vencimiento, p.estimadas)} · ${desglose(p)}`;
    $('#t-comprometido').textContent = pesos(panel.comprometidoSiguientes);
    $('#t-comprometido-nota').textContent = panel.siguientes.length
      ? `En ${panel.siguientes.length === 1 ? '1 resumen' : `${panel.siguientes.length} resúmenes`} después del próximo`
      : 'Sin cuotas después del próximo';
    nota.textContent = p.estimadas
      ? `Las del próximo resumen son una estimación (≈ ${fechaCorta(p.cierre)} y ≈ ${fechaCorta(p.vencimiento)}). Cuando las sepas, cargalas: las compras se reasignan solas.`
      : 'Si cambian, cargalas de nuevo: las compras se reasignan solas.';
    const form = $('#t-form-fechas');
    form.elements.cierre.value = p.cierre;
    form.elements.vencimiento.value = p.vencimiento;
    $('#t-resumenes').replaceChildren(
      ...panel.porPagar.map((r) => bloqueResumen(r, 'Resumen cerrado por pagar')),
      bloqueResumen(p, 'Próximo resumen'),
      ...panel.pagados.map((r) => bloqueResumen(r, `Resumen que venció el ${fechaCorta(r.vencimiento)}`)));
    $('#t-siguientes').replaceChildren(...(panel.siguientes.length ? panel.siguientes.map(filaSiguiente)
      : [Iconos.vacio('layers', 'No hay cuotas en los resúmenes siguientes.')]));
  }

  async function guardarFechas(e) {
    e.preventDefault();
    const form = e.currentTarget;
    const error = $('#t-fechas-error');
    mensajeError(error, null);
    const cierre = form.elements.cierre.value;
    const vencimiento = form.elements.vencimiento.value;
    if (!cierre || !vencimiento) { mensajeError(error, new Error('Elegí el cierre y el vencimiento')); return; }
    try {
      await api('/api/tarjeta/fechas', { method: 'POST', headers: JSON_HEADERS,
        body: JSON.stringify({ tarjetaId: elegida, cierre, vencimiento }) });
      toast('Fechas guardadas');
      await vista();
    } catch (err) {
      mensajeError(error, err);
    }
  }

  // --- Tus tarjetas: agregar, editar y dar de baja ---

  async function vistaTarjetas() {
    const [tarjetas, catalogo] = await Promise.all([api('/api/tarjetas'), api('/api/catalogo')]);
    const cuentas = $('#t-form-tarjeta').elements.cuentaDebitoId;
    if (cuentas.options.length === 1) {
      cuentas.append(...catalogo.cuentas.map((c) => h('option', { value: c.id }, c.nombre)));
    }
    $('#t-lista-tarjetas').replaceChildren(...(tarjetas.length ? tarjetas.map(filaTarjeta)
      : [Iconos.vacio('credit-card', 'Todavía no cargaste ninguna tarjeta.')]));
  }

  function filaTarjeta(t) {
    const editar = h('button', { type: 'button', class: 'mini' }, 'Editar');
    editar.addEventListener('click', () => abrirTarjeta(t));
    const estado = h('button', { type: 'button', class: 'mini' }, t.activa ? 'Dar de baja' : 'Reactivar');
    estado.addEventListener('click', () => cambiarEstado(t));
    return h('div', { class: `fila-tarjeta${t.activa ? '' : ' inactiva'}` },
      inicial(t),
      h('div', { class: 'datos' },
        h('b', {}, t.nombre), t.activa ? null : h('span', { class: 'chip estimado' }, 'dada de baja'), h('br'),
        h('span', { class: 'note' }, [t.banco, t.cuentaDebito ? `se paga desde ${t.cuentaDebito}` : 'sin cuenta de pago',
          t.alias ? `alias: ${t.alias}` : 'sin alias'].filter(Boolean).join(' · '))),
      editar, estado);
  }

  function abrirTarjeta(t) {
    const form = $('#t-form-tarjeta');
    form.reset();
    mensajeError($('#t-tarjeta-error'), null);
    const e = form.elements;
    e.id.value = t ? t.id : '';
    e.nombre.value = t ? t.nombre : '';
    e.banco.value = t && t.banco ? t.banco : '';
    e.cuentaDebitoId.value = t && t.cuentaDebitoId != null ? String(t.cuentaDebitoId) : '';
    e.alias.value = t && t.alias ? t.alias : '';
    e.color.value = t && t.color ? t.color : '#2D6CDF';
    e.inicial.value = t && t.inicial ? t.inicial : '';
    form.hidden = false;
    e.nombre.focus();
  }

  async function guardarTarjeta(evento) {
    evento.preventDefault();
    const form = evento.currentTarget;
    const e = form.elements;
    const error = $('#t-tarjeta-error');
    mensajeError(error, null);
    if (!e.nombre.value.trim()) { mensajeError(error, new Error('Poné el nombre de la tarjeta')); return; }
    const cuerpo = {
      nombre: e.nombre.value, banco: e.banco.value || null,
      cuentaDebitoId: e.cuentaDebitoId.value ? Number(e.cuentaDebitoId.value) : null,
      color: e.color.value || null, inicial: e.inicial.value || null, alias: e.alias.value || null,
    };
    try {
      await api(e.id.value ? `/api/tarjetas/${e.id.value}` : '/api/tarjetas', {
        method: e.id.value ? 'PUT' : 'POST', headers: JSON_HEADERS, body: JSON.stringify(cuerpo) });
      form.hidden = true;
      toast('Tarjeta guardada');
      await vista();
    } catch (err) {
      mensajeError(error, err);
    }
  }

  async function cambiarEstado(t) {
    try {
      await api(`/api/tarjetas/${t.id}/${t.activa ? 'baja' : 'reactivar'}`, { method: 'POST' });
      toast(t.activa ? `${t.nombre} dada de baja: lo que ya cargaste queda` : `${t.nombre} activa de nuevo`);
      await vista();
    } catch (err) {
      mensajeError($('#t-tarjeta-error'), err);
    }
  }

  $('#t-agregar').addEventListener('click', () => abrirTarjeta(null));
  $('#t-cancelar-tarjeta').addEventListener('click', () => { $('#t-form-tarjeta').hidden = true; });
  $('#t-form-tarjeta').addEventListener('submit', guardarTarjeta);
  $('#t-form-fechas').addEventListener('submit', guardarFechas);
  $('#t-form-dolar').addEventListener('submit', guardarDolar);

  window.Gastito.alCargarResumen = [...(window.Gastito.alCargarResumen || []), enResumen];
  window.Gastito.alCargarTarjeta = [...(window.Gastito.alCargarTarjeta || []), vista];
})();
