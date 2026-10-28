'use strict';

/*
 * Dashboard de Gastito. Solo pide los datos a la API y los muestra: todos los totales, promedios y porcentajes
 * vienen calculados del backend (así la misma API sirve para la Mini App). Acá solo se formatea.
 */
(() => {
  const TAMANIO_PAGINA = 20;
  const ULTIMOS_MOVIMIENTOS = 5;

  const estado = {
    vista: 'resumen',
    mes: null,          // 'YYYY-MM' que se está viendo
    hoyMes: mesDeHoy(), // el mes actual, para no ir a meses futuros
    catalogo: null,
    resumen: null,
    pagina: 0,
    grafico: null,
    graficoRecibos: null,
  };

  // --- Formatos: solo presentación ---

  const enteros = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 0 });
  const conDecimales = new Intl.NumberFormat('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  function pesos(n) {
    const v = Number(n);
    const texto = Number.isInteger(v) ? enteros.format(Math.abs(v)) : conDecimales.format(Math.abs(v));
    return (v < 0 ? '-$' : '$') + texto;
  }

  function dolares(n) {
    const v = Number(n);
    const decimales = v > 0 && v < 0.01 ? 4 : 2;
    return 'US$ ' + new Intl.NumberFormat('es-AR', { minimumFractionDigits: decimales, maximumFractionDigits: decimales }).format(v);
  }

  function mesDeHoy() {
    const hoy = new Date();
    return `${hoy.getFullYear()}-${String(hoy.getMonth() + 1).padStart(2, '0')}`;
  }

  function sumarMeses(mes, cantidad) {
    const [anio, numero] = mes.split('-').map(Number);
    const fecha = new Date(Date.UTC(anio, numero - 1 + cantidad, 1));
    return `${fecha.getUTCFullYear()}-${String(fecha.getUTCMonth() + 1).padStart(2, '0')}`;
  }

  function nombreMes(mes) {
    const [anio, numero] = mes.split('-').map(Number);
    return new Intl.DateTimeFormat('es-AR', { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(anio, numero - 1, 1)));
  }

  const mayuscula = (texto) => texto.charAt(0).toUpperCase() + texto.slice(1);
  const mesYAnio = (mes) => `${mayuscula(nombreMes(mes))} ${mes.slice(0, 4)}`;

  /** "2026-10-21T16:42:00" → "21/10 16:42"; "2026-10-01" → "01/10". */
  function fechaCorta(iso) {
    const [fecha, hora] = iso.split('T');
    const [, mes, dia] = fecha.split('-');
    return hora ? `${dia}/${mes} ${hora.slice(0, 5)}` : `${dia}/${mes}`;
  }

  const plural = (n, uno, varios) => `${n} ${n === 1 ? uno : varios}`;
  const origenTexto = (origen) => mayuscula(origen.toLowerCase());

  // --- DOM ---

  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];

  /** Crea un elemento. Los textos van siempre como texto, nunca como HTML. */
  function h(tag, props = {}, ...hijos) {
    const el = document.createElement(tag);
    for (const [clave, valor] of Object.entries(props)) {
      if (valor == null || valor === false) continue;
      if (clave === 'class') el.className = valor;
      else if (clave === 'style') Object.assign(el.style, valor);
      else if (clave.startsWith('on')) el.addEventListener(clave.slice(2), valor);
      else el.setAttribute(clave, valor === true ? '' : valor);
    }
    for (const hijo of hijos.flat()) {
      if (hijo != null && hijo !== false) el.append(hijo instanceof Node ? hijo : String(hijo));
    }
    return el;
  }

  const texto = (id, valor) => { $(id).textContent = valor; };

  function filaMensaje(tbody, columnas, mensaje) {
    tbody.replaceChildren(h('tr', {}, h('td', { class: 'mensaje', colspan: columnas }, mensaje)));
  }

  function debounce(fn, ms) {
    let espera;
    return (...args) => { clearTimeout(espera); espera = setTimeout(() => fn(...args), ms); };
  }

  // --- API ---

  async function api(ruta, opciones = {}) {
    let respuesta;
    try {
      respuesta = await fetch(ruta, {
        ...opciones,
        credentials: 'same-origin',
        headers: { Accept: 'application/json', ...(opciones.headers || {}) },
      });
    } catch {
      throw new Error('No pude conectarme con Gastito. ¿Está prendido?');
    }
    if (respuesta.status === 204) return null;
    let cuerpo = null;
    try { cuerpo = await respuesta.json(); } catch { /* sin cuerpo JSON */ }
    if (!respuesta.ok) throw new Error(cuerpo && cuerpo.error ? cuerpo.error : `Error ${respuesta.status}`);
    return cuerpo;
  }

  const conMes = (ruta, extra = {}) => {
    const params = new URLSearchParams({ mes: estado.mes, ...extra });
    for (const [k, v] of [...params]) if (v === '' || v == null) params.delete(k);
    return `${ruta}?${params}`;
  };

  // --- Avisos y carga ---

  function mostrarAviso(mensaje) {
    texto('#aviso-texto', mensaje);
    $('#aviso').hidden = false;
  }

  function ocultarAviso() { $('#aviso').hidden = true; }

  function cargando(si) { $('main').classList.toggle('cargando', si); }

  // --- Navegación ---

  const VISTAS = ['resumen', 'movimientos', 'ingresos', 'fijos'];

  function leerUrl() {
    const vista = location.hash.slice(1);
    estado.vista = VISTAS.includes(vista) ? vista : 'resumen';
    const mes = new URLSearchParams(location.search).get('mes');
    estado.mes = /^\d{4}-\d{2}$/.test(mes || '') && mes <= estado.hoyMes ? mes : estado.hoyMes;
  }

  function escribirUrl() {
    const params = estado.mes === estado.hoyMes ? '' : `?mes=${estado.mes}`;
    history.replaceState(null, '', `${location.pathname}${params}#${estado.vista}`);
  }

  function mostrarVista() {
    for (const vista of VISTAS) $(`#v-${vista}`).hidden = vista !== estado.vista;
    for (const a of $$('nav a[data-view]')) a.classList.toggle('active', a.dataset.view === estado.vista);
  }

  function irA(vista) {
    estado.vista = vista;
    estado.pagina = 0;
    escribirUrl();
    mostrarVista();
    window.scrollTo(0, 0);
    cargar();
  }

  function cambiarMes(delta) {
    const nuevo = sumarMeses(estado.mes, delta);
    if (nuevo > estado.hoyMes) return;
    estado.mes = nuevo;
    estado.pagina = 0;
    escribirUrl();
    cargar();
  }

  // --- Carga de cada vista ---

  async function cargar() {
    cargando(true);
    ocultarAviso();
    try {
      const resumen = await api(conMes('/api/resumen'));
      estado.resumen = resumen;
      encabezado(resumen);
      const tareas = [api(conMes('/api/costos-ia')).then(costosIa)];
      if (estado.vista === 'resumen') {
        tarjetasResumen(resumen);
        tareas.push(
          api(conMes('/api/gastos/por-dia')).then(graficoPorDia),
          api(conMes('/api/gastos/por-categoria')).then(categorias),
          api(conMes('/api/gastos/por-billetera')).then(billeteras),
          api(conMes('/api/ingresos')).then(ingresosDelResumen),
          api('/api/resumenes/ultimo').then(loQueNotoLaIa),
          ultimosMovimientos(),
        );
      } else if (estado.vista === 'movimientos') {
        texto('#m-sub', `${mesYAnio(resumen.mes)} · ${plural(resumen.cantidadGastos, 'gasto', 'gastos')} · ${pesos(resumen.gastado)}`);
        tareas.push(tablaMovimientos());
      } else if (estado.vista === 'ingresos') {
        tareas.push(
          api(conMes('/api/ingresos')).then(vistaIngresos),
          api(`/api/ingresos/por-mes?hasta=${estado.mes}&meses=6`).then(ingresosPorMes),
          api(`/api/recibos/serie?hasta=${estado.mes}&meses=12`).then(serieRecibos),
        );
        disponible(resumen.disponible, '#i-disponible', '#i-disponible-barra', '#i-disponible-nota');
      } else {
        tareas.push(
          api(conMes('/api/fijos/checklist')).then(vistaFijosChecklist),
          api('/api/fijos').then(fijosAbm),
        );
      }
      await Promise.all(tareas);
    } catch (error) {
      mostrarAviso(`No pude cargar los datos: ${error.message}`);
    } finally {
      cargando(false);
    }
  }

  function encabezado(resumen) {
    for (const el of $$('[data-titulo-mes]')) el.textContent = mesYAnio(resumen.mes);
    for (const el of $$('[data-mes-largo]')) el.textContent = nombreMes(resumen.mes);
    for (const el of $$('[data-nombre-mes="-1"]')) el.textContent = mayuscula(nombreMes(sumarMeses(resumen.mes, -1)));
    for (const el of $$('[data-nombre-mes="1"]')) el.textContent = mayuscula(nombreMes(sumarMeses(resumen.mes, 1)));
    for (const b of $$('[data-mes="1"]')) b.hidden = resumen.mes >= estado.hoyMes;
    for (const a of $$('[data-csv]')) {
      a.href = `/api/movimientos.csv?mes=${resumen.mes}`;
      a.setAttribute('download', `gastito-${resumen.mes}.csv`);
    }
    texto('#r-sub', resumen.mesActual
      ? `Al ${resumen.diasContados} de ${nombreMes(resumen.mes)}`
      : resumen.diasContados === 0 ? 'Todavía no empezó' : 'Mes completo');
  }

  function costosIa(c) {
    texto('#ia-titulo', `Costo de la IA en ${nombreMes(c.mes)}`);
    texto('#ia-costo', dolares(c.costoUsd));
    texto('#ia-llamadas', plural(c.llamadas, 'llamada', 'llamadas'));
  }

  // --- Resumen ---

  function tarjetasResumen(r) {
    texto('#r-gastado', pesos(r.gastado));
    const c = r.comparacion;
    const comparacion = $('#r-comparacion');
    if (c.direccion === 'SIN_DATOS') {
      comparacion.textContent = c.conDatos
        ? `Sin gastos en ${nombreMes(c.mes)} para comparar`
        : `Sin datos de ${nombreMes(c.mes)} para comparar`;
    } else {
      const clase = c.direccion === 'SUBE' ? 'up' : c.direccion === 'BAJA' ? 'down' : '';
      const signo = c.variacionPorcentaje > 0 ? '+' : '';
      comparacion.replaceChildren(
        h('span', { class: clase }, `${signo}${c.variacionPorcentaje}%`),
        ` vs. ${nombreMes(c.mes)} ${c.mesCompleto ? 'completo' : `al día ${c.hastaDia}`}`,
      );
    }

    texto('#r-ingresos', pesos(r.ingresos.total));
    const partes = [];
    if (Number(r.ingresos.sueldo) > 0) partes.push(`Sueldo ${pesos(r.ingresos.sueldo)}`);
    if (Number(r.ingresos.aguinaldo) > 0) partes.push(`aguinaldo ${pesos(r.ingresos.aguinaldo)}`);
    if (Number(r.ingresos.extras) > 0) partes.push(`extras ${pesos(r.ingresos.extras)}`);
    texto('#r-ingresos-detalle', partes.length ? partes.join(' · ') : 'Todavía no cargaste ingresos');

    disponible(r.disponible, '#r-disponible', '#r-disponible-barra', '#r-disponible-nota');

    texto('#r-promedio', pesos(r.promedioDiario));
    texto('#r-cantidad', `${plural(r.cantidadGastos, 'gasto', 'gastos')} en el mes`);

    const vacio = r.cantidadGastos === 0;
    $('#r-vacio').hidden = !vacio;
    $('#r-graficos').hidden = vacio;
  }

  function disponible(d, idMonto, idBarra, idNota) {
    const monto = $(idMonto);
    const barra = $(idBarra);
    if (!d) {
      monto.textContent = '—';
      monto.classList.remove('negativo');
      barra.hidden = true;
      texto(idNota, 'Cargá tus ingresos para ver cuánto te queda');
      return;
    }
    monto.textContent = pesos(d.monto);
    monto.classList.toggle('negativo', Number(d.monto) < 0);
    barra.hidden = false;
    const relleno = barra.querySelector('i');
    relleno.style.width = `${Math.min(d.porcentajeGastado, 100)}%`;
    relleno.classList.toggle('pasado', d.porcentajeGastado > 100);
    texto(idNota, `Gastaste el ${d.porcentajeGastado}% de lo que cobraste`);
  }

  function color(variable) {
    return getComputedStyle(document.documentElement).getPropertyValue(variable).trim();
  }

  function graficoPorDia(p) {
    const lienzo = $('#grafico-dias');
    if (estado.grafico) estado.grafico.destroy();
    if (!window.Chart) {
      lienzo.replaceWith(h('p', { class: 'note', id: 'grafico-dias' }, 'No se pudo cargar el gráfico.'));
      return;
    }
    const barras = p.dias.map((d) => (d.futuro ? null : Number(d.total)));
    const promedio = p.dias.map((d) => (d.futuro ? null : Number(p.promedio)));
    const colores = p.dias.map((d) => (d.dia === p.diaMaximo ? color('--bar-dia-max') : color('--bar-dia')));
    estado.grafico = new window.Chart(lienzo, {
      data: {
        labels: p.dias.map((d) => d.dia),
        datasets: [
          { type: 'bar', label: 'Gasto', data: barras, backgroundColor: colores, borderRadius: 4, order: 2 },
          {
            type: 'line', label: 'Promedio', data: promedio, borderColor: color('--linea-promedio'),
            borderDash: [6, 4], borderWidth: 1.5, pointRadius: 0, order: 1,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (items) => `Día ${items[0].label}`,
              label: (item) => `${item.dataset.label}: ${pesos(item.raw)}`,
            },
          },
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: color('--muted'), maxRotation: 0, autoSkipPadding: 8 } },
          y: {
            beginAtZero: true,
            grid: { color: color('--row') },
            ticks: { color: color('--muted'), callback: (v) => pesos(v), maxTicksLimit: 5 },
          },
        },
      },
    });
  }

  function categorias(lista) {
    const contenedor = $('#r-categorias');
    if (!lista.length) {
      contenedor.replaceChildren(h('p', { class: 'note' }, 'Sin gastos este mes.'));
      return;
    }
    contenedor.replaceChildren(...lista.map((c) => h('div', { class: 'cat', title: plural(c.cantidad, 'gasto', 'gastos') },
      h('span', {}, `${c.emoji ? c.emoji + ' ' : ''}${c.nombre}`),
      h('div', { class: 'track' }, h('i', { style: { width: `${c.porcentajeDelMaximo}%` } })),
      h('span', { class: 'money' }, pesos(c.total)),
    )));
  }

  function billeteras(lista) {
    $('#r-billetera-barra').replaceChildren(...lista.map((b) => h('i', {
      style: { width: `${b.porcentaje}%`, background: b.color },
      title: `${b.nombre}: ${pesos(b.total)} (${b.porcentaje}%)`,
    })));
    $('#r-billetera-leyenda').replaceChildren(...lista.map((b) => h('span', {
      class: b.tipo === 'SIN_ESPECIFICAR' ? 'sin-especificar' : null,
      title: b.tipo === 'SIN_ESPECIFICAR' ? 'Gastos sin medio de pago ni cuenta: corregilos desde el bot' : null,
    }, h('i', { style: { background: b.color } }), `${b.nombre} `, h('span', { class: 'money' }, pesos(b.total)))));
  }

  function ingresosDelResumen(r) {
    const lista = $('#r-ingresos-lista');
    if (!r.ingresos.length) {
      lista.replaceChildren(h('p', { class: 'note' }, 'Todavía no hay ingresos este mes.'));
      return;
    }
    lista.replaceChildren(...r.ingresos.map((i) => h('div', { class: 'ing' },
      h('span', {}, [i.descripcion || i.claseNombre, fechaCorta(i.fecha), i.billetera].join(' · ')),
      h('span', { class: 'money' }, pesos(i.monto)),
    )));
  }

  // --- Lo que notó la IA ---

  const diaYMes = new Intl.DateTimeFormat('es-AR', { day: 'numeric', month: 'long', timeZone: 'UTC' });
  const diaSemana = new Intl.DateTimeFormat('es-AR', { weekday: 'long', day: 'numeric', timeZone: 'UTC' });
  const fecha = (iso) => new Date(`${iso}T00:00:00Z`);

  /** El último resumen (semanal o mensual): de qué período es, si es parcial, y sus observaciones. */
  function loQueNotoLaIa(r) {
    const lista = $('#r-ia');
    if (!r) {
      texto('#r-ia-periodo', '');
      lista.replaceChildren(h('p', { class: 'note' },
        'Todavía no hay resúmenes. El domingo a las 21 llega el de la semana, o pedilo con /resumen.'));
      return;
    }
    const periodo = r.tipo === 'SEMANAL'
      ? `Semana del ${diaYMes.format(fecha(r.desde))} al ${diaYMes.format(fecha(r.fin))}`
      : mesYAnio(r.desde.slice(0, 7));
    texto('#r-ia-periodo', r.parcial
      ? `${periodo} · ${r.tipo === 'SEMANAL' ? 'semana' : 'mes'} parcial, hasta el ${diaSemana.format(fecha(r.hasta))}`
      : periodo);
    const notas = (r.notas || []).map((n) => h('p', { class: 'note' }, `ℹ️ ${n}`));
    const sinConsejo = r.sinConsejo ? h('p', { class: 'consejo' }, `👉 ${r.sinConsejo}`) : null;
    let aviso = null;
    if (r.textoIaDescartado) {
      aviso = 'Esta vez la IA escribió un número que no estaba en los datos, así que el resumen salió solo con los números.';
    } else if (!r.iaRespondio) {
      aviso = 'Este resumen salió sin el texto de la IA.';
    } else if (!r.observaciones.length && !r.consejo && !r.sinConsejo) {
      aviso = 'Esta vez no hubo nada nuevo para comentar más allá de los números.';
    }
    lista.replaceChildren(...[
      aviso ? h('p', { class: 'note' }, aviso) : null,
      r.observaciones.length ? h('ul', { class: 'observaciones' }, ...r.observaciones.map((o) => h('li', {}, o))) : null,
      r.consejo ? h('p', { class: 'consejo' }, h('b', {}, '👉 Consejo: '), r.consejo) : null,
      sinConsejo,
      ...notas,
    ].filter(Boolean));
  }

  // --- Tablas de movimientos ---

  function filasMovimientos(tbody, pagina, sinResultados) {
    if (!pagina.elementos.length) {
      filaMensaje(tbody, 6, sinResultados);
      return;
    }
    tbody.replaceChildren(...pagina.elementos.map(filaMovimiento));
  }

  function filaMovimiento(m) {
    const boton = m.ticket
      ? h('button', { class: 'mini desplegar', type: 'button', 'aria-expanded': 'false' }, '🧾 Productos')
      : null;
    const fila = h('tr', {},
      h('td', { class: 'date' }, fechaCorta(m.fechaHora)),
      h('td', {}, h('b', {}, m.descripcion || '—'), boton),
      h('td', {}, m.categoria ? `${m.emoji ? m.emoji + ' ' : ''}${m.categoria}` : '—'),
      h('td', { class: m.sinEspecificar ? 'sin-especificar' : null }, m.medio),
      h('td', {}, h('span', { class: `chip ${m.origen.toLowerCase()}` }, origenTexto(m.origen))),
      h('td', { class: 'amt' }, pesos(m.monto)),
    );
    if (boton) boton.addEventListener('click', () => desplegarTicket(boton, fila, m.id));
    return fila;
  }

  /** Abre (o cierra) debajo de la fila los productos del ticket. Se piden a la API recién al abrir. */
  async function desplegarTicket(boton, fila, id) {
    const abierta = fila.nextElementSibling;
    if (abierta && abierta.classList.contains('detalle-ticket')) {
      abierta.remove();
      boton.setAttribute('aria-expanded', 'false');
      return;
    }
    boton.disabled = true;
    try {
      const detalle = await api(`/api/movimientos/${id}/items`);
      fila.after(h('tr', { class: 'detalle-ticket' }, h('td', { colspan: 6 }, contenidoTicket(detalle))));
      boton.setAttribute('aria-expanded', 'true');
    } catch (error) {
      mostrarAviso(`No pude cargar el ticket: ${error.message}`);
    } finally {
      boton.disabled = false;
    }
  }

  const cantidades = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

  /** La frase de una cuenta del ticket que no cierra. Los montos vienen calculados; acá solo se formatean. */
  function diferenciaTicket(dif) {
    const calculado = pesos(Math.abs(dif.calculado));
    const impreso = pesos(Math.abs(dif.impreso));
    switch (dif.control) {
      case 'PRODUCTOS_Y_SUBTOTAL': return `Los productos suman ${calculado} y el subtotal dice ${impreso}.`;
      case 'DESCUENTOS': return `Los descuentos suman ${calculado} y el ticket dice ${impreso}.`;
      case 'SUBTOTAL_Y_TOTAL': return `El subtotal menos los descuentos da ${calculado} y el total dice ${impreso}.`;
      default: return `Los productos suman ${calculado} y el total dice ${impreso}.`;
    }
  }

  function contenidoTicket(d) {
    const productos = d.items.length
      ? h('ul', { class: 'productos' }, ...d.items.map((i) => h('li', { class: i.descuento ? 'descuento' : null },
        h('span', { title: [i.descripcionOriginal, i.codigoBarras].filter(Boolean).join(' · ') || null }, i.descripcion,
          i.cantidad != null && Number(i.cantidad) !== 1 ? h('span', { class: 'note' }, ` ×${cantidades.format(i.cantidad)}`) : null),
        h('span', { class: 'money' }, pesos(i.subtotal)),
      )))
      : h('p', { class: 'note' }, 'No se leyeron los productos de este ticket.');
    const avisos = d.coincide ? null : h('div', { class: 'avisos-ticket' },
      ...d.diferencias.map((dif) => h('p', { class: 'up' }, `⚠️ ${diferenciaTicket(dif)}`)),
      ...d.renglonesDudosos.map((r) => h('p', { class: 'up' },
        `⚠️ ${r.descripcion}: ${cantidades.format(r.cantidad)} × ${pesos(r.precioUnitario)} da ${pesos(r.calculado)}, pero el renglón dice ${pesos(r.impreso)}.`)),
      d.lineasIlegibles.length ? h('p', { class: 'note' }, '🔍 No se pudo leer bien:') : null,
      d.lineasIlegibles.length ? h('ul', { class: 'ilegibles' }, ...d.lineasIlegibles.map((l) => h('li', {}, l))) : null);
    const suma = d.sumaItems != null && d.coincide
      ? h('span', { class: 'note' }, `Las cuentas cierran: ${pesos(d.total)}.`)
      : null;
    const sucursal = d.sucursal ? h('span', { class: 'note' }, `Sucursal ${d.sucursal}`) : null;
    const foto = d.foto
      ? h('a', { class: 'ver-todos', href: `/api/movimientos/${d.id}/foto`, target: '_blank', rel: 'noopener' }, 'Ver foto')
      : h('span', { class: 'note' }, 'Sin foto');
    return [productos, avisos, h('div', { class: 'pie-ticket' }, suma, sucursal, foto)];
  }

  async function ultimosMovimientos() {
    const pagina = await api(conMes('/api/movimientos', {
      q: $('#r-q').value.trim(), categoria: $('#r-cat').value, tamanio: ULTIMOS_MOVIMIENTOS,
    }));
    filasMovimientos($('#r-movimientos'), pagina,
      $('#r-q').value || $('#r-cat').value ? 'No hay gastos que coincidan.' : 'Todavía no hay gastos este mes.');
  }

  async function tablaMovimientos() {
    const pagina = await api(conMes('/api/movimientos', {
      q: $('#m-q').value.trim(), categoria: $('#m-cat').value, medioPago: $('#m-med').value,
      origen: $('#m-ori').value, pagina: estado.pagina, tamanio: TAMANIO_PAGINA,
    }));
    filasMovimientos($('#m-movimientos'), pagina, 'No hay gastos que coincidan con los filtros.');
    const desde = pagina.totalElementos === 0 ? 0 : pagina.pagina * pagina.tamanio + 1;
    const hasta = pagina.pagina * pagina.tamanio + pagina.elementos.length;
    texto('#m-pagina', `Mostrando ${desde}–${hasta} de ${pagina.totalElementos}`);
    $('#m-anterior').disabled = pagina.pagina === 0;
    $('#m-siguiente').disabled = pagina.pagina + 1 >= pagina.totalPaginas;
  }

  async function recargarTabla(funcion) {
    try {
      await funcion();
    } catch (error) {
      mostrarAviso(`No pude cargar los movimientos: ${error.message}`);
    }
  }

  // --- Ingresos ---

  function vistaIngresos(r) {
    texto('#i-total', pesos(r.totales.total));
    texto('#i-sueldo', pesos(r.totales.sueldo));
    texto('#i-aguinaldo', pesos(r.totales.aguinaldo));
    texto('#i-extras', pesos(r.totales.extras));
    texto('#i-extras-nota', r.totales.cantidadExtras ? plural(r.totales.cantidadExtras, 'ingreso extra', 'ingresos extra') : '');

    const tbody = $('#i-detalle');
    if (!r.ingresos.length) {
      filaMensaje(tbody, 6, 'Todavía no hay ingresos este mes. Cargalos con el botón o con /sueldo en el bot.');
      return;
    }
    tbody.replaceChildren(...r.ingresos.map(filaIngreso));
  }

  // --- Recibos de sueldo ---

  const GRUPOS_DE_RECIBO = [
    { clave: 'REMUNERATIVO', titulo: 'Remunerativo' },
    { clave: 'NO_REMUNERATIVO', titulo: 'No remunerativo' },
    { clave: 'DESCUENTO', titulo: 'Descuentos' },
  ];

  /** Abre (o cierra) debajo del ingreso los conceptos del recibo. Se piden a la API recién al abrir. */
  async function desplegarRecibo(boton, fila, id) {
    const abierta = fila.nextElementSibling;
    if (abierta && abierta.classList.contains('detalle-recibo')) {
      abierta.remove();
      boton.setAttribute('aria-expanded', 'false');
      return;
    }
    boton.disabled = true;
    try {
      const detalle = await api(`/api/recibos/${id}`);
      fila.after(h('tr', { class: 'detalle-recibo' }, h('td', { colspan: 6 }, contenidoRecibo(detalle))));
      boton.setAttribute('aria-expanded', 'true');
    } catch (error) {
      mostrarAviso(`No pude cargar el recibo: ${error.message}`);
    } finally {
      boton.disabled = false;
    }
  }

  function contenidoRecibo(d) {
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
      h('ul', {},
        h('li', { class: 'total' }, h('span', {}, 'Bruto'), h('span', { class: 'money' }, pesos(d.bruto))),
        h('li', {}, h('span', {}, 'Descuentos'), h('span', { class: 'money' }, '−' + pesos(d.descuentos))),
        h('li', { class: 'total' }, h('span', {}, 'Neto'), h('span', { class: 'money' }, pesos(d.neto)))),
      d.aviso ? h('p', { class: 'aviso-recibo' }, `⚠️ ${d.aviso}`) : null,
      d.conArchivo
        ? h('a', { class: 'ver-todos', href: `/api/recibos/${d.movimientoId}/archivo`, target: '_blank', rel: 'noopener' }, 'Ver recibo')
        : null);
  }

  /** Una línea: "Contra septiembre: +10% (+$89.000)". Los números llegan hechos; acá solo se formatean. */
  function lineaCambio(texto, c) {
    if (c.direccion === 'SIN_DATOS') {
      return h('div', { class: 'note' }, `${texto}: sin recibo para comparar`);
    }
    const signo = Number(c.diferencia) > 0 ? '+' : '';
    const porcentaje = c.variacionPorcentaje == null ? '' : `${c.variacionPorcentaje > 0 ? '+' : ''}${c.variacionPorcentaje}% `;
    const clase = c.direccion === 'SUBE' ? 'mejora' : c.direccion === 'BAJA' ? 'empeora' : null;
    return h('div', {}, `${texto}: `, h('span', { class: clase }, `${porcentaje}(${signo}${pesos(c.diferencia)})`));
  }

  function serieRecibos(s) {
    const seccion = $('#i-recibos');
    const hay = s.meses.some((m) => m.conRecibo);
    seccion.hidden = !hay;
    if (estado.graficoRecibos) estado.graficoRecibos.destroy();
    estado.graficoRecibos = null;
    if (!hay) return;
    $('#i-recibos-variacion').replaceChildren(
      h('div', {}, `Neto de ${nombreMes(s.hasta)}: `, h('b', {}, s.netoActual == null ? 'sin recibo' : pesos(s.netoActual))),
      lineaCambio(`Contra ${nombreMes(s.contraMesAnterior.mes)}`, s.contraMesAnterior),
      lineaCambio(`Contra ${mesYAnio(s.contraMismoMesAnioAnterior.mes)}`, s.contraMismoMesAnioAnterior));
    const lienzo = $('#grafico-recibos');
    if (!window.Chart) return;
    const serie = (campo) => s.meses.map((m) => (m.conRecibo ? Number(m[campo]) : null));
    estado.graficoRecibos = new window.Chart(lienzo, {
      type: 'bar',
      data: {
        labels: s.meses.map((m) => mayuscula(nombreMes(m.mes)).slice(0, 3) + (m.mes.endsWith('-01') ? ` ${m.mes.slice(2, 4)}` : '')),
        datasets: [
          { label: 'Bruto', data: serie('bruto'), backgroundColor: color('--bar-dia'), borderRadius: 4 },
          { label: 'Descuentos', data: serie('descuentos'), backgroundColor: color('--warn'), borderRadius: 4 },
          { label: 'Neto', data: serie('neto'), backgroundColor: color('--accent'), borderRadius: 4 },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: {
          legend: { position: 'bottom', labels: { color: color('--muted'), boxWidth: 12 } },
          tooltip: { callbacks: { label: (item) => `${item.dataset.label}: ${pesos(item.raw)}` } },
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: color('--muted'), maxRotation: 0 } },
          y: { beginAtZero: true, grid: { color: color('--row') }, ticks: { color: color('--muted'), callback: (v) => pesos(v), maxTicksLimit: 5 } },
        },
      },
    });
  }

  function filaIngreso(i) {
    const boton = i.origen === 'RECIBO'
      ? h('button', { class: 'mini desplegar', type: 'button', 'aria-expanded': 'false' }, '💼 Recibo')
      : null;
    const fila = h('tr', {},
      h('td', { class: 'date' }, fechaCorta(i.fecha)),
      h('td', {}, h('span', { class: `chip ${i.clase.toLowerCase()}` }, i.claseNombre)),
      h('td', {}, i.descripcion || '—', boton),
      h('td', { class: i.billetera === 'Sin especificar' ? 'sin-especificar' : null }, i.billetera),
      h('td', { class: 'amt' }, pesos(i.monto)),
      h('td', {}, h('button', { type: 'button', class: 'mini', onclick: () => borrarIngreso(i) }, 'Borrar')),
    );
    if (boton) boton.addEventListener('click', () => desplegarRecibo(boton, fila, i.id));
    return fila;
  }

  function ingresosPorMes(meses) {
    const anio = estado.mes.slice(0, 4);
    $('#i-meses').replaceChildren(...meses.map((m) => h('div', { class: 'cat' },
      h('span', {}, mayuscula(nombreMes(m.mes)) + (m.mes.slice(0, 4) !== anio ? ` ${m.mes.slice(0, 4)}` : '')),
      h('div', { class: 'track' }, h('i', { style: { width: `${m.porcentajeDelMaximo}%` } })),
      h('span', { class: 'money' }, pesos(m.total)),
    )));
  }

  async function borrarIngreso(ingreso) {
    const detalle = ingreso.descripcion ? ` (${ingreso.descripcion})` : '';
    if (!confirm(`¿Borrar el ingreso de ${pesos(ingreso.monto)}${detalle}?`)) return;
    try {
      await api(`/api/ingresos/${ingreso.id}`, { method: 'DELETE' });
      cargar();
    } catch (error) {
      mostrarAviso(`No pude borrar el ingreso: ${error.message}`);
    }
  }

  // --- Formulario de ingresos ---

  function abrirFormulario() {
    const form = $('#form-ingreso');
    form.reset();
    $('#form-error').hidden = true;
    const hoy = new Date();
    const hoyIso = `${estado.hoyMes}-${String(hoy.getDate()).padStart(2, '0')}`;
    // En el mes actual, hoy; en uno anterior, el primer día de ese mes.
    $('#i-fecha').value = estado.mes === estado.hoyMes ? hoyIso : `${estado.mes}-01`;
    $('#i-fecha').max = hoyIso;
    $('#modal').hidden = false;
    $('#i-monto').focus();
  }

  function cerrarFormulario() { $('#modal').hidden = true; }

  async function guardarIngreso(evento) {
    evento.preventDefault();
    const boton = $('#form-guardar');
    const error = $('#form-error');
    error.hidden = true;
    boton.disabled = true;
    try {
      await api('/api/ingresos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          clase: $('#i-clase').value,
          monto: $('#i-monto').value,
          descripcion: $('#i-desc').value,
          cuentaId: $('#i-cuenta').value ? Number($('#i-cuenta').value) : null,
          fecha: $('#i-fecha').value || null,
        }),
      });
      cerrarFormulario();
      cargar();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
    } finally {
      boton.disabled = false;
    }
  }

  // --- Fijos: checklist mensual ---

  /** "32.900" o "32900,50" → 32900 / 32900.5, para mandar como número JSON. */
  function numeroArg(texto) {
    const limpio = String(texto).trim().replace(/\./g, '').replace(',', '.');
    return Number(limpio);
  }

  function estadoChecklist(i) {
    if (i.estado === 'CARGADO') return `✅ Cargado${i.porcentaje != null ? ` (${i.porcentaje > 0 ? '+' : ''}${i.porcentaje}%)` : ''}`;
    if (i.estado === 'ESTE_MES_NO') return '➖ Este mes no';
    return '⏳ Falta';
  }

  function accionesChecklist(i) {
    if (i.estado === 'FALTA') {
      return h('div', { class: 'row-btns' },
        h('button', { type: 'button', class: 'mini', onclick: () => cargarFijo(i) }, 'Cargar monto'),
        h('button', { type: 'button', class: 'mini', onclick: () => esteMesNoFijo(i) }, 'Este mes no'));
    }
    return h('div', { class: 'row-btns' },
      h('button', { type: 'button', class: 'mini', onclick: () => deshacerFijo(i) }, 'Deshacer'));
  }

  function vistaFijosChecklist(data) {
    texto('#f-cargado', pesos(data.cargado));
    texto('#f-faltan', data.faltan);
    const tbody = $('#f-checklist');
    if (!data.items.length) { filaMensaje(tbody, 5, 'No tenés gastos fijos este mes.'); return; }
    tbody.replaceChildren(...data.items.map((i) => h('tr', {},
      h('td', {}, `${i.emoji ? i.emoji + ' ' : ''}${i.nombre}`),
      h('td', {}, estadoChecklist(i)),
      h('td', { class: 'amt' }, i.estado === 'CARGADO' ? pesos(i.monto) : i.montoAnterior != null ? `ref. ${pesos(i.montoAnterior)}` : '—'),
      h('td', {}, i.estado === 'CARGADO' ? fechaCorta(i.fecha) : `vence el ${i.vencimiento.slice(-2)}`),
      h('td', {}, accionesChecklist(i)),
    )));
  }

  async function cargarFijo(item) {
    const respuesta = window.prompt(`¿Cuánto gastaste en ${item.nombre}?`);
    if (!respuesta) return;
    const monto = numeroArg(respuesta);
    if (!Number.isFinite(monto) || monto <= 0) { mostrarAviso('No entendí el monto.'); return; }
    try {
      await api(`/api/fijos/${item.fijoId}/cargar`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ monto }),
      });
      cargar();
    } catch (e) {
      mostrarAviso(`No pude cargar el gasto: ${e.message}`);
    }
  }

  async function esteMesNoFijo(item) {
    try {
      await api(`/api/fijos/${item.fijoId}/este-mes-no?mes=${estado.mes}`, { method: 'POST' });
      cargar();
    } catch (e) {
      mostrarAviso(`No pude marcar "este mes no": ${e.message}`);
    }
  }

  async function deshacerFijo(item) {
    if (!confirm(`¿Deshacer ${item.nombre} de este mes?`)) return;
    try {
      await api(`/api/fijos/${item.fijoId}/deshacer?mes=${estado.mes}`, { method: 'POST' });
      cargar();
    } catch (e) {
      mostrarAviso(`No pude deshacer: ${e.message}`);
    }
  }

  // --- Fijos: ABM ---

  function fijosAbm(lista) {
    estado.fijos = lista;
    const tbody = $('#f-abm');
    if (!lista.length) { filaMensaje(tbody, 7, 'No tenés gastos fijos. Agregá el primero con el botón de arriba.'); return; }
    tbody.replaceChildren(...lista.map((f) => h('tr', { class: f.activo ? null : 'inactivo' },
      h('td', {}, f.nombre),
      h('td', {}, f.categoria),
      h('td', { class: 'amt' }, pesos(f.montoEsperado)),
      h('td', {}, f.diaDelMes),
      h('td', {}, f.frecuencia === 'BIMESTRAL' ? 'Cada dos meses' : 'Mensual'),
      h('td', {}, f.proponerMonto ? 'Sí' : 'No'),
      h('td', {}, h('div', { class: 'row-btns' },
        h('button', { type: 'button', class: 'mini', onclick: () => abrirFormularioFijo(f) }, 'Editar'),
        f.activo ? h('button', { type: 'button', class: 'mini', onclick: () => bajaFijo(f) }, 'Dar de baja') : null,
      )),
    )));
  }

  async function bajaFijo(f) {
    if (!confirm(`¿Dar de baja ${f.nombre}? No se borra el historial.`)) return;
    try {
      await api(`/api/fijos/${f.id}`, { method: 'DELETE' });
      cargar();
    } catch (e) {
      mostrarAviso(`No pude dar de baja el fijo: ${e.message}`);
    }
  }

  function abrirFormularioFijo(f) {
    const form = $('#form-fijo');
    form.reset();
    $('#form-fijo-error').hidden = true;
    $('#f-id').value = f ? f.id : '';
    $('#form-fijo-titulo').textContent = f ? `Editar ${f.nombre}` : 'Nuevo gasto fijo';
    $('#f-nombre').value = f ? f.nombre : '';
    $('#f-monto').value = f ? f.montoEsperado : '';
    $('#f-categoria').value = f ? f.categoriaId : '';
    $('#f-dia').value = f ? f.diaDelMes : '';
    $('#f-frecuencia').value = f ? f.frecuencia : 'MENSUAL';
    $('#f-medio').value = f && f.medioPagoId ? f.medioPagoId : '';
    $('#f-cuenta').value = f && f.cuentaId ? f.cuentaId : '';
    $('#f-tarjeta').checked = !!(f && f.tarjeta);
    $('#f-alias').value = f && f.alias ? f.alias : '';
    $('#f-variable').checked = !!(f && f.montoVariable);
    $('#f-proponer').checked = !!(f && f.proponerMonto);
    $('#modal-fijo').hidden = false;
    $('#f-nombre').focus();
  }

  function cerrarFormularioFijo() { $('#modal-fijo').hidden = true; }

  async function guardarFijo(evento) {
    evento.preventDefault();
    const boton = $('#form-fijo-guardar');
    const error = $('#form-fijo-error');
    error.hidden = true;
    boton.disabled = true;
    const id = $('#f-id').value;
    const cuerpo = {
      nombre: $('#f-nombre').value,
      monto: numeroArg($('#f-monto').value),
      categoriaId: Number($('#f-categoria').value),
      medioPagoId: $('#f-medio').value ? Number($('#f-medio').value) : null,
      cuentaId: $('#f-cuenta').value ? Number($('#f-cuenta').value) : null,
      tarjeta: $('#f-tarjeta').checked,
      diaDelMes: Number($('#f-dia').value),
      frecuencia: $('#f-frecuencia').value,
      montoVariable: $('#f-variable').checked,
      alias: $('#f-alias').value || null,
      proponerMonto: $('#f-proponer').checked,
    };
    try {
      await api(id ? `/api/fijos/${id}` : '/api/fijos', {
        method: id ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cuerpo),
      });
      cerrarFormularioFijo();
      cargar();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
    } finally {
      boton.disabled = false;
    }
  }

  // --- Catálogo (filtros y formulario) ---

  function llenarCatalogo(c) {
    estado.catalogo = c;
    for (const select of $$('select[data-categorias]')) {
      select.append(...c.categorias.map((cat) => h('option', { value: cat.id }, `${cat.emoji ? cat.emoji + ' ' : ''}${cat.nombre}`)));
    }
    $('#m-med').append(...c.mediosPago.map((m) => h('option', { value: m.id }, m.nombre)));
    $('#i-clase').replaceChildren(...c.clasesIngreso.map((cl) => h('option', { value: cl.clave },
      cl.clave === 'EXTRA' ? 'Extra (trabajo particular, una venta, un regalo…)' : cl.nombre)));
    $('#i-cuenta').replaceChildren(...c.cuentas.map((cu) => h('option', { value: cu.id }, cu.nombre)),
      h('option', { value: '' }, 'Efectivo'));
    $('#f-medio').append(...c.mediosPago.map((m) => h('option', { value: m.id }, m.nombre)));
    $('#f-cuenta').append(...c.cuentas.map((cu) => h('option', { value: cu.id }, cu.nombre)));
  }

  // --- Eventos ---

  function conectar() {
    document.addEventListener('click', (e) => {
      const vista = e.target.closest('[data-view]');
      if (vista) { e.preventDefault(); irA(vista.dataset.view); return; }
      const mes = e.target.closest('[data-mes]');
      if (mes) { cambiarMes(Number(mes.dataset.mes)); return; }
      if (e.target.closest('[data-abrir-ingreso]')) { abrirFormulario(); return; }
      if (e.target.closest('[data-cerrar]') || e.target.id === 'modal') cerrarFormulario();
      if (e.target.closest('[data-abrir-fijo]')) { abrirFormularioFijo(null); return; }
      if (e.target.closest('[data-cerrar-fijo]') || e.target.id === 'modal-fijo') cerrarFormularioFijo();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      if (!$('#modal').hidden) cerrarFormulario();
      if (!$('#modal-fijo').hidden) cerrarFormularioFijo();
    });
    window.addEventListener('hashchange', () => { leerUrl(); mostrarVista(); cargar(); });
    $('#reintentar').addEventListener('click', () => cargar());
    $('#form-ingreso').addEventListener('submit', guardarIngreso);
    $('#form-fijo').addEventListener('submit', guardarFijo);

    const resumenFiltro = debounce(() => recargarTabla(ultimosMovimientos), 300);
    $('#r-q').addEventListener('input', resumenFiltro);
    $('#r-cat').addEventListener('change', () => recargarTabla(ultimosMovimientos));

    const movimientosFiltro = () => { estado.pagina = 0; recargarTabla(tablaMovimientos); };
    $('#m-q').addEventListener('input', debounce(movimientosFiltro, 300));
    for (const id of ['#m-cat', '#m-med', '#m-ori']) $(id).addEventListener('change', movimientosFiltro);
    $('#m-anterior').addEventListener('click', () => { estado.pagina--; recargarTabla(tablaMovimientos); });
    $('#m-siguiente').addEventListener('click', () => { estado.pagina++; recargarTabla(tablaMovimientos); });

    // El gráfico toma los colores del tema: si cambia el modo oscuro, se redibuja.
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (estado.vista === 'resumen') cargar();
    });
  }

  async function iniciar() {
    leerUrl();
    escribirUrl();
    mostrarVista();
    conectar();
    try {
      llenarCatalogo(await api('/api/catalogo'));
    } catch (error) {
      mostrarAviso(`No pude cargar las categorías: ${error.message}`);
    }
    await cargar();
  }

  iniciar();
})();
