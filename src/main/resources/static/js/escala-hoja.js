'use strict';

/*
 * La escala desde la hoja del sindicato, en Ingresos → Calculadora → Escalas y conceptos: subir el Excel, ver el
 * resumen por mes (cambios contra la escala anterior, lo que se mantiene y el control con "Valores netos") y guardar
 * los meses elegidos; descargar la plantilla, y los nombres de fila (alias) que reconoce cada concepto. Igual que el
 * resto del dashboard, los cambios en % y el control llegan hechos de la API.
 */
(() => {
  const { h, api, pesos, mostrarAviso, estado } = window.Gastito;
  const { Iconos } = window;
  const $ = (selector) => document.querySelector(selector);

  const JSON_HEADERS = { 'Content-Type': 'application/json' };
  const DESTINOS_FIJOS = [
    { clave: 'HORA_NORMAL', nombre: 'Hora normal' },
    { clave: 'VALORES_NETOS', nombre: 'Valores netos (control)' },
  ];
  const datos = { conceptos: [], alias: [], importacion: null, elegidos: new Set() };

  const porcentaje = (n) => `${Number(n).toLocaleString('es-AR', { maximumFractionDigits: 2 })} %`;
  const valor = (clase, n) => (clase === 'PORCENTAJE' ? porcentaje(n) : pesos(n));
  const variacion = (n) => (n == null || Number(n) === 0 ? ''
    : ` (${Number(n) > 0 ? '+' : '−'}${Math.abs(Number(n)).toLocaleString('es-AR', { maximumFractionDigits: 1 })}%)`);

  // --- Importar ---

  function dibujarImportar() {
    const archivo = h('input', { type: 'file', accept: '.xlsx,.xls', 'aria-label': 'Excel con la escala' });
    const mes = h('input', { type: 'month', 'aria-label': 'Mes, si la planilla es de un solo mes y no lo dice' });
    const error = h('p', { class: 'error', role: 'alert', hidden: true });
    const leer = h('button', { type: 'submit', class: 'primary' }, 'Leer Excel');
    const form = h('form', { class: 'escala-form', novalidate: true },
      h('label', { class: 'calc-campo' }, h('span', {}, 'Excel de la hoja del sindicato (o la plantilla)'), archivo),
      h('label', { class: 'calc-campo' }, h('span', {}, 'Mes (solo si la planilla es de un mes y no lo dice)'), mes),
      error,
      h('div', { class: 'btns' }, leer,
        h('a', { class: 'mini', href: `/api/sueldo/escalas/plantilla?mes=${estado.mes}`, download: '' }, 'Descargar plantilla')),
      h('p', { class: 'note' }, 'Una columna por mes, con el mes arriba (oct-26). Lo que la hoja no trae (el adicional CCT, los descuentos) se mantiene de la escala anterior. Las celdas con fórmulas no se leen. También podés mandar el Excel o una foto de la hoja al bot.'));
    form.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      error.hidden = true;
      if (!archivo.files.length) { error.textContent = 'Elegí el Excel con la escala'; error.hidden = false; return; }
      const cuerpo = new FormData();
      cuerpo.append('archivo', archivo.files[0]);
      if (mes.value) cuerpo.append('mes', mes.value);
      leer.disabled = true;
      try {
        datos.importacion = await api('/api/sueldo/escalas/importar', { method: 'POST', body: cuerpo });
        datos.elegidos = new Set(datos.importacion.meses.map((m) => m.mes));
        dibujarPropuesta();
      } catch (e) {
        error.textContent = e.message;
        error.hidden = false;
      } finally {
        leer.disabled = false;
      }
    });
    $('#calc-hoja').replaceChildren(h('h3', {}, 'Cargar desde la hoja del sindicato'), form, h('div', { id: 'calc-hoja-propuesta', class: 'recibo' }));
  }

  function dibujarPropuesta() {
    const contenedor = $('#calc-hoja-propuesta');
    const imp = datos.importacion;
    if (!imp) { contenedor.replaceChildren(); return; }
    const meses = imp.meses.map((m) => {
      const marca = h('input', { type: 'checkbox', checked: datos.elegidos.has(m.mes), 'aria-label': `Guardar ${m.etiqueta}` });
      marca.addEventListener('change', () => {
        if (marca.checked) datos.elegidos.add(m.mes); else datos.elegidos.delete(m.mes);
      });
      const control = m.control == null ? h('p', { class: 'note' }, 'La hoja no tiene «Valores netos» para controlar.')
        : m.control.ok ? h('p', { class: 'mejora con-icono' }, Iconos.svg('circle-check'), `Control: los valores netos dan ${pesos(m.control.calculado)}, igual que la hoja`)
          : h('p', { class: 'aviso-recibo' }, `La hoja no cierra: con el ${porcentaje(m.control.porcentaje)} que descuenta la hoja da ${pesos(m.control.calculado)} y «Valores netos» dice ${pesos(m.control.hoja)}. Revisá el Excel.`);
      const cambian = m.leidos.filter((v) => v.antes != null && Number(v.antes) !== Number(v.ahora));
      return h('div', {},
        h('h3', {}, h('label', {}, marca, ` ${m.etiqueta}`), m.reemplaza ? ' · ya tiene escala: se reemplaza' : ''),
        h('ul', {}, ...m.leidos.map((v) => h('li', {}, h('span', {}, v.nombre), h('span', {}, `${valor(v.clase, v.ahora)}${variacion(v.variacion)}`)))),
        m.reemplaza ? h('p', { class: 'note' }, cambian.length
          ? `Cambia: ${cambian.map((v) => `${v.nombre} ${valor(v.clase, v.antes)} → ${valor(v.clase, v.ahora)}`).join(' · ')}`
          : 'La hoja no cambia nada de la escala que ya está.') : null,
        m.mantenidos.length ? h('p', { class: 'note' }, `Se mantienen: ${m.mantenidos.map((v) => `${v.nombre} ${valor(v.clase, v.ahora)}${v.clase === 'PORCENTAJE' && v.antes == null ? ' (por defecto)' : ''}`).join(', ')}`) : null,
        control);
    });
    const guardar = h('button', { type: 'button', class: 'primary' }, 'Guardar los meses marcados');
    guardar.addEventListener('click', async () => {
      guardar.disabled = true;
      try {
        const guardadas = await api(`/api/sueldo/escalas/importaciones/${imp.id}`, {
          method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ meses: [...datos.elegidos] }),
        });
        datos.importacion = null;
        dibujarPropuesta();
        Iconos.toast(`Escalas guardadas: ${guardadas.map((g) => g.etiqueta + (g.reemplazada ? ' (reemplazada)' : '')).join(', ')}`);
        await window.Gastito.recargarCalculadora();
      } catch (e) {
        mostrarAviso(`No pude guardar las escalas: ${e.message}`);
      } finally {
        guardar.disabled = false;
      }
    });
    const descartar = h('button', { type: 'button', class: 'mini' }, 'Descartar');
    descartar.addEventListener('click', async () => {
      await api(`/api/sueldo/escalas/importaciones/${imp.id}`, { method: 'DELETE' }).catch(() => null);
      datos.importacion = null;
      dibujarPropuesta();
    });
    contenedor.replaceChildren(
      h('h3', {}, 'Escalas desde la hoja'),
      ...meses,
      imp.ignoradas.length ? h('p', { class: 'note' }, `No uso estas filas: ${imp.ignoradas.join(', ')}`) : null,
      h('div', { class: 'btns' }, guardar, descartar));
  }

  // --- Nombres de fila ---

  const nombreDestino = (a) => (a.destino === 'CONCEPTO'
    ? (datos.conceptos.find((c) => c.id === a.conceptoId) || { nombre: '¿?' }).nombre
    : DESTINOS_FIJOS.find((d) => d.clave === a.destino).nombre);

  function dibujarAlias() {
    const filas = datos.alias.map((a) => {
      const borrar = h('button', { type: 'button', class: 'mini' }, 'Quitar');
      borrar.addEventListener('click', async () => {
        try {
          await api(`/api/sueldo/alias/${a.id}`, { method: 'DELETE' });
          await cargarAlias();
        } catch (e) {
          mostrarAviso(`No pude quitar el nombre: ${e.message}`);
        }
      });
      return h('tr', {}, h('td', {}, a.alias), h('td', {}, nombreDestino(a)), h('td', {}, borrar));
    });
    const alias = h('input', { type: 'text', maxlength: 100, 'aria-label': 'Nombre de la fila', placeholder: 'Como está en la hoja' });
    const destino = h('select', { 'aria-label': 'A qué corresponde' },
      ...DESTINOS_FIJOS.map((d) => h('option', { value: d.clave }, d.nombre)),
      ...datos.conceptos.filter((c) => c.activo).map((c) => h('option', { value: `c${c.id}` }, c.nombre)));
    const agregar = h('button', { type: 'button', class: 'mini' }, 'Agregar');
    agregar.addEventListener('click', async () => {
      const esConcepto = destino.value.startsWith('c');
      try {
        await api('/api/sueldo/alias', {
          method: 'POST', headers: JSON_HEADERS,
          body: JSON.stringify({ alias: alias.value, destino: esConcepto ? 'CONCEPTO' : destino.value, conceptoId: esConcepto ? Number(destino.value.slice(1)) : null }),
        });
        await cargarAlias();
        Iconos.toast('Nombre agregado');
      } catch (e) {
        mostrarAviso(`No pude agregar el nombre: ${e.message}`);
      }
    });
    $('#calc-alias').replaceChildren(
      h('h3', {}, 'Nombres de fila de la hoja'),
      h('p', { class: 'note' }, 'Cómo se llama cada concepto en la hoja del sindicato. Se compara sin mayúsculas, acentos, espacios ni puntos (A.I.S. = AIS). Las filas que no están acá (ni con el nombre del concepto) no se usan.'),
      h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, ...['Fila de la hoja', 'Corresponde a', ''].map((t) => h('th', {}, t)))),
        h('tbody', {}, ...filas, h('tr', {}, h('td', {}, alias), h('td', {}, destino), h('td', {}, agregar))))));
  }

  async function cargarAlias() {
    datos.alias = await api('/api/sueldo/alias');
    dibujarAlias();
  }

  async function alCargarIngresos() {
    datos.conceptos = await api('/api/sueldo/conceptos');
    dibujarImportar();
    dibujarPropuesta();
    await cargarAlias();
  }

  window.Gastito.alCargarIngresos = [...(window.Gastito.alCargarIngresos || []), alCargarIngresos];
})();
