'use strict';

/*
 * Los viajes: una pestaña por viaje con Pagado · Falta ≈ · Total estimado (y la barra contra el presupuesto, si lo
 * hay), cuánto ahorrar por sueldo, lo que falta pagar (se tilda y se crea el gasto), lo que me costó a mí contra lo que
 * puse por los demás, los gastos por categoría y por día y lo que me deben por el viaje. No calcula nada: los totales,
 * los porcentajes y el ahorro llegan hechos de la API.
 */
(() => {
  const { h, api, pesos, fechaCorta, toast, numeroArg } = window.Gastito;
  const { Iconos } = window;
  const $ = (selector) => document.querySelector(selector);
  const JSON_HEADERS = { 'Content-Type': 'application/json' };
  const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });
  const ESTADOS = { PROXIMO: 'Próximo', EN_CURSO: 'En curso', TERMINADO: 'Terminado' };

  let viajes = [];
  let elegido = null;
  let catalogo = null;
  let grafico = null;

  const hoyIso = () => new Date().toLocaleDateString('sv-SE');
  const mensajeError = (error) => { const el = $('#vj-error'); el.textContent = error ? error.message : ''; el.hidden = !error; };
  const actual = () => viajes.find((v) => v.id === elegido) || null;
  const nombreConEmoji = (v) => (v.emoji ? `${v.emoji} ${v.nombre}` : v.nombre);

  async function enviar(ruta, cuerpo, exito, metodo = 'POST') {
    try {
      const r = await api(ruta, { method: metodo, headers: JSON_HEADERS, body: cuerpo == null ? undefined : JSON.stringify(cuerpo) });
      toast(exito);
      cerrarEditor();
      if (r && r.id) elegido = r.id;
      await vista();
    } catch (err) {
      const el = $('#vj-editor .error');
      if (el && !$('#vj-editor').hidden) { el.textContent = err.message; el.hidden = false; } else mensajeError(err);
    }
  }

  // --- La pestaña ---

  async function vista() {
    mensajeError(null);
    [viajes, catalogo] = await Promise.all([api('/api/viajes'), catalogo ? Promise.resolve(catalogo) : api('/api/catalogo')]);
    const vacio = viajes.length === 0;
    $('#vj-vacio').hidden = !vacio;
    $('#vj-contenido').hidden = vacio;
    if (vacio) { $('#vj-pestanas').replaceChildren(); return; }
    if (!actual()) {
      // El que está en curso o el próximo; si no, el más reciente.
      const enCurso = viajes.find((v) => v.estado === 'EN_CURSO');
      const proximos = viajes.filter((v) => v.estado === 'PROXIMO');
      elegido = (enCurso || proximos[proximos.length - 1] || viajes[0]).id;
    }
    pestanas();
    mostrar(actual());
  }

  function pestanas() {
    $('#vj-pestanas').replaceChildren(...viajes.map((v) => {
      const b = h('button', { type: 'button', class: 'pestana', role: 'tab', 'aria-selected': String(v.id === elegido) },
        nombreConEmoji(v), h('span', { class: 'note' }, ` · ${ESTADOS[v.estado] || ''}`));
      b.addEventListener('click', () => { elegido = v.id; pestanas(); mostrar(v); });
      return b;
    }));
  }

  function mostrar(v) {
    $('#vj-fechas').textContent = `${nombreConEmoji(v)} · del ${fechaCorta(v.desde)} al ${fechaCorta(v.hasta)} (${v.dias} días)`
      + (v.personas ? ` · ${v.personas} ${v.personas === 1 ? 'persona' : 'personas'}` : '') + ` · ${ESTADOS[v.estado] || ''}`;
    $('#vj-pagado').textContent = pesos(v.pagado);
    $('#vj-pagado-nota').textContent = 'Tus gastos guardados del viaje (tu parte)';
    $('#vj-falta').textContent = pesos(v.falta);
    $('#vj-falta-nota').textContent = v.sinMonto ? `Sin contar ${v.sinMonto === 1 ? '1 pendiente sin monto' : `${v.sinMonto} pendientes sin monto`}` : 'Lo que falta pagar con monto';
    $('#vj-total').textContent = pesos(v.totalEstimado);
    const barra = $('#vj-presupuesto-barra');
    if (v.presupuesto != null) {
      const pct = Number(v.porcentajePresupuesto);
      barra.hidden = false;
      const relleno = barra.querySelector('i');
      relleno.style.width = `${Math.min(100, pct)}%`;
      relleno.classList.toggle('pasado', pct > 100);
      $('#vj-presupuesto-nota').textContent = `${NUMERO.format(pct)} % del presupuesto de ${pesos(v.presupuesto)}`;
    } else {
      barra.hidden = true;
      $('#vj-presupuesto-nota').textContent = 'Sin presupuesto';
    }
    $('#vj-deben').textContent = pesos(v.meDeben);
    ahorro(v);
    pendientes(v);
    $('#vj-mio').textContent = pesos(v.pagado);
    $('#vj-demas').textContent = pesos(v.porLosDemas);
    categorias(v);
    porDia(v);
    deudas(v);
    $('#vj-notas').textContent = v.notas || 'Sin notas.';
  }

  function ahorro(v) {
    const a = v.ahorro;
    $('#vj-ahorro-card').hidden = !a;
    if (!a) return;
    const partes = [`Para ${v.nombre} falta pagar ≈ `, h('b', { class: 'money' }, pesos(a.falta)),
      v.sinMonto ? ' (sin contar lo que no tiene monto).' : '.'];
    if (Number(a.reservado) > 0) {
      partes.push(` Tenés ${pesos(a.reservado)} reservados en inversiones`,
        Number(a.aSeparar) === 0 ? ': alcanza.' : `, así que faltan ≈ ${pesos(a.aSeparar)}.`);
    }
    if (Number(a.aSeparar) > 0) {
      if (a.cobros === 0) partes.push(' No quedan cobros de sueldo antes del viaje.');
      else partes.push(` Hasta el inicio ${a.cobros === 1 ? 'queda 1 cobro' : `quedan ${a.cobros} cobros`} de sueldo: separá ≈ `,
        h('b', { class: 'money' }, pesos(a.porMes)), ' por mes.');
    }
    $('#vj-ahorro').replaceChildren(...partes);
  }

  function pendientes(v) {
    const items = v.pendientes.map((p) => {
      const check = h('input', { type: 'checkbox', checked: p.pagado, disabled: p.pagado, 'aria-label': `Pagado: ${p.descripcion}` });
      check.addEventListener('change', () => { check.checked = false; editorPagar(p); });
      const monto = p.pagado ? `Pagado ${pesos(p.montoPagado)}` : p.montoAproximado != null ? `≈ ${pesos(p.montoAproximado)}` : 'sin monto';
      return h('div', { class: 'pendiente' }, check,
        h('div', { class: 'crece' }, p.pagado ? h('s', {}, p.descripcion) : h('b', {}, p.descripcion), ` · ${monto}`),
        p.pagado ? null : h('div', { class: 'botones' },
          h('button', { type: 'button', class: 'mini', onclick: () => editorPendiente(p) }, Iconos.svg('pencil'), 'Editar'),
          h('button', { type: 'button', class: 'mini peligro', onclick: () => {
            if (confirm(`¿Sacar ${p.descripcion} de lo que falta pagar?`)) enviar(`/api/viajes/pendientes/${p.id}`, null, 'Sacado', 'DELETE');
          } }, Iconos.svg('x'), 'Sacar')));
    });
    $('#vj-pendientes').replaceChildren(...(items.length ? items
      : [h('p', { class: 'note' }, 'No anotaste nada. Decíselo al bot ("para Bariloche me falta el hospedaje, ≈ 400.000") o tocá Agregar.')]));
  }

  function categorias(v) {
    $('#vj-categorias').replaceChildren(...(v.porCategoria.length ? v.porCategoria.map((c) => {
      const info = Iconos.categoriaPorNombre(c.categoria);
      return h('div', { class: 'cat' },
        h('span', { class: 'cat-nombre' }, Iconos.iconoCategoria(info), h('span', { class: 'cat-texto' }, c.categoria)),
        h('div', { class: 'track' }, h('i', { style: { width: `${c.porcentaje}%`, background: Iconos.colorDe(info) } })),
        h('span', { class: 'money' }, pesos(c.total)));
    }) : [Iconos.vacio('inbox', 'Todavía no hay gastos del viaje.')]));
  }

  function color(variable) {
    return getComputedStyle(document.documentElement).getPropertyValue(variable).trim();
  }

  function porDia(v) {
    if (grafico) grafico.destroy();
    grafico = new window.Chart($('#vj-grafico'), {
      type: 'bar',
      data: {
        labels: v.porDia.map((d) => fechaCorta(d.dia)),
        datasets: [{ label: 'Gastado', data: v.porDia.map((d) => Number(d.total)), backgroundColor: color('--accent'), borderRadius: 4 }],
      },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => pesos(c.parsed.y) } } },
        scales: {
          x: { grid: { display: false }, ticks: { color: color('--muted'), maxRotation: 0, autoSkipPadding: 8 } },
          y: { grid: { color: color('--row') }, ticks: { color: color('--muted'), callback: (x) => pesos(x), maxTicksLimit: 5 } },
        },
      },
    });
  }

  function deudas(v) {
    const items = [
      ...v.prestamos.map((p) => h('div', { class: 'pendiente' }, Iconos.svg('banknote'),
        h('div', { class: 'crece' }, h('b', {}, p.persona), ` · préstamo de ${pesos(p.monto)}`,
          p.estado === 'TERMINADO' ? ' · ya te devolvió todo'
            : ` · ${p.cuotasCobradas} de ${p.cantidadCuotas} cuotas, faltan ${pesos(p.restante)}`))),
      ...v.juntadas.map((j) => h('div', { class: 'pendiente' }, Iconos.svg('users'),
        h('div', { class: 'crece' }, h('b', {}, j.descripcion || 'Juntada'), ` · ${fechaCorta(j.fechaHora)}`,
          Number(j.aCobrar) > 0 ? ` · te deben ${pesos(j.aCobrar)}` : ' · ya no te deben nada'))),
    ];
    $('#vj-deudas').replaceChildren(...(items.length ? items
      : [h('p', { class: 'note' }, 'Nadie te debe nada del viaje. Si le pagaste algo a alguien, decíselo al bot ("30.000 son de Martín, que me los devuelve en 6 cuotas").')]));
  }

  // --- Editores ---

  function cerrarEditor() { $('#vj-editor').hidden = true; $('#vj-editor').replaceChildren(); }

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
    const editor = $('#vj-editor');
    editor.replaceChildren(...[h('h2', {}, titulo), nota ? h('p', { class: 'note' }, nota) : null, form, error].filter(Boolean));
    editor.hidden = false;
    editor.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    const primero = form.querySelector('input:not([type=hidden]), select');
    if (primero) primero.focus();
  }

  const campo = (texto, control) => h('label', {}, texto, ' ', control);
  const inputTexto = (name, valor, extra = {}) => h('input', { name, value: valor || '', autocomplete: 'off', ...extra });
  const inputMonto = (name, valor) => h('input', { name, inputmode: 'decimal', autocomplete: 'off', placeholder: 'Opcional',
    value: valor == null ? '' : NUMERO.format(Number(valor)) });
  const inputFecha = (name, valor) => h('input', { name, type: 'date', value: valor || hoyIso() });

  function montoOpcional(valor, mensaje) {
    if (!valor || !valor.trim()) return null;
    const n = numeroArg(valor);
    if (!Number.isFinite(n) || n <= 0) throw new Error(mensaje);
    return n;
  }

  function selectCategoria(valor, conVacio = true) {
    return h('select', { name: 'categoriaId' }, ...(conVacio ? [h('option', { value: '' }, 'Sin elegir')] : []),
      ...catalogo.categorias.map((c) => h('option', { value: c.id, selected: c.id === valor }, `${c.emoji || ''} ${c.nombre}`.trim())));
  }

  function editorViaje(v) {
    abrirEditor(v ? `Editar ${v.nombre}` : 'Nuevo viaje',
      'Entre las fechas (incluidas), lo que cargues en el bot queda en el viaje. La cantidad de personas y el presupuesto son opcionales.',
      [campo('Nombre', inputTexto('nombre', v && v.nombre, { required: true, maxlength: 100, placeholder: 'Bariloche' })),
        campo('Emoji', inputTexto('emoji', v && v.emoji, { maxlength: 4, size: 3, placeholder: 'Solo' })),
        campo('Desde', inputFecha('desde', v && v.desde)), campo('Hasta', inputFecha('hasta', v && v.hasta)),
        campo('Personas', h('input', { name: 'personas', type: 'number', min: 1, max: 99, value: v && v.personas ? v.personas : '', placeholder: 'Opcional' })),
        campo('Presupuesto', inputMonto('presupuesto', v && v.presupuesto)),
        campo('Notas', inputTexto('notas', v && v.notas, { maxlength: 1000 }))],
      (e) => {
        if (!e.nombre.value.trim()) throw new Error('Poné el nombre del viaje');
        if (!e.desde.value || !e.hasta.value) throw new Error('Poné las fechas');
        const cuerpo = {
          nombre: e.nombre.value, emoji: e.emoji.value || null, desde: e.desde.value, hasta: e.hasta.value,
          personas: e.personas.value ? Number(e.personas.value) : null,
          presupuesto: montoOpcional(e.presupuesto.value, 'Escribí el presupuesto, por ejemplo 1.500.000'), notas: e.notas.value || null,
        };
        if (v) enviar(`/api/viajes/${v.id}`, cuerpo, 'Guardado', 'PUT'); else enviar('/api/viajes', cuerpo, 'Viaje guardado');
      });
  }

  function editorPendiente(p) {
    const v = actual();
    abrirEditor(p ? `Editar ${p.descripcion}` : `Algo que falta pagar de ${v.nombre}`, 'El monto aproximado es opcional: lo que no tiene monto no entra en "falta".',
      [campo('Qué', inputTexto('descripcion', p && p.descripcion, { required: true, maxlength: 255, placeholder: 'Hospedaje' })),
        campo('Monto ≈', inputMonto('monto', p && p.montoAproximado)), campo('Categoría', selectCategoria(p && p.categoriaId))],
      (e) => {
        if (!e.descripcion.value.trim()) throw new Error('Decí qué falta pagar');
        const cuerpo = { descripcion: e.descripcion.value, montoAproximado: montoOpcional(e.monto.value, 'Escribí el monto, por ejemplo 400.000'),
          categoriaId: e.categoriaId.value ? Number(e.categoriaId.value) : null };
        if (p) enviar(`/api/viajes/pendientes/${p.id}`, cuerpo, 'Guardado', 'PUT');
        else enviar(`/api/viajes/${v.id}/pendientes`, cuerpo, 'Anotado');
      });
  }

  function editorPagar(p) {
    const medio = h('select', { name: 'medio' }, h('option', { value: '' }, 'Sin especificar'),
      ...catalogo.mediosConTarjeta.map((m) => h('option', { value: m.id }, m.nombre)));
    abrirEditor(`Pagué ${p.descripcion}`, 'Se crea el gasto del viaje, guardado. Cuenta como gasto tuyo.',
      [campo('Monto', inputMonto('monto', p.montoAproximado)), campo('Categoría', selectCategoria(p.categoriaId, false)),
        campo('Medio', medio), campo('Fecha', inputFecha('fecha'))],
      (e) => {
        const monto = montoOpcional(e.monto.value, 'Escribí el monto, por ejemplo 380.000');
        if (monto == null) throw new Error('Escribí cuánto pagaste');
        enviar(`/api/viajes/pendientes/${p.id}/pagar`, {
          monto, categoriaId: Number(e.categoriaId.value), medioPagoId: e.medio.value ? Number(e.medio.value) : null, fecha: e.fecha.value,
        }, 'Pagado');
      }, 'Guardar el gasto');
  }

  $('#vj-nuevo').addEventListener('click', () => editorViaje(null));
  $('#vj-editar').addEventListener('click', () => editorViaje(actual()));
  $('#vj-agregar-pendiente').addEventListener('click', () => editorPendiente(null));
  $('#vj-borrar').addEventListener('click', () => {
    const v = actual();
    if (v && confirm(`¿Borrar el viaje ${v.nombre}? Los gastos quedan, sin viaje.`)) {
      elegido = null;
      enviar(`/api/viajes/${v.id}`, null, 'Viaje borrado', 'DELETE');
    }
  });

  window.Gastito.alCargarViajes = [...(window.Gastito.alCargarViajes || []), vista];
})();
