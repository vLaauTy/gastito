/* Íconos (Lucide, copia local en /img/icons.svg), colores por categoría, avisos cortos y estados vacíos. Solo presentación. */
(() => {
  'use strict';

  const NS = 'http://www.w3.org/2000/svg';
  const COLORES = 13;

  /** Un ícono del sprite. Toma el color del texto que lo rodea. */
  function svg(nombre, clase = '') {
    const el = document.createElementNS(NS, 'svg');
    el.setAttribute('class', `i ${clase}`.trim());
    el.setAttribute('aria-hidden', 'true');
    const uso = document.createElementNS(NS, 'use');
    uso.setAttribute('href', `/img/icons.svg#${nombre}`);
    el.append(uso);
    return el;
  }

  const span = (clase, ...hijos) => {
    const el = document.createElement('span');
    el.className = clase;
    el.append(...hijos);
    return el;
  };

  const sinTildes = (t) => String(t || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

  // --- Categorías: ícono (de la base) y un color fijo por categoría ---

  const porNombre = new Map();
  const porEmoji = new Map();
  let lista = [];

  function cargarCategorias(categorias) {
    lista = [...categorias].sort((a, b) => a.id - b.id);
    porNombre.clear();
    porEmoji.clear();
    lista.forEach((c, i) => {
      const info = { id: c.id, nombre: c.nombre, icono: c.icono || 'circle-ellipsis', color: (i % COLORES) + 1 };
      porNombre.set(c.nombre, info);
      if (c.emoji) porEmoji.set(c.emoji, info);
    });
  }

  const categoriaPorNombre = (nombre) => porNombre.get(nombre) || null;
  const categoriaPorEmoji = (emoji) => porEmoji.get(emoji) || null;

  const colorDe = (info) => `var(--c${info ? info.color : COLORES})`;

  /** Ícono de la categoría en su color. */
  function iconoCategoria(info) {
    const el = svg(info ? info.icono : 'circle-ellipsis', 'cat-ico');
    el.style.color = colorDe(info);
    return el;
  }

  /** La categoría como etiqueta: ícono + nombre, con el color de la categoría. */
  function etiquetaCategoria(nombre) {
    if (!nombre) return document.createTextNode('—');
    const info = categoriaPorNombre(nombre);
    const el = span('chip-cat', svg(info ? info.icono : 'circle-ellipsis'), nombre);
    el.style.setProperty('--cat', colorDe(info));
    return el;
  }

  // --- Medios de pago y billeteras ---

  function nombreIconoMedio(medio) {
    const t = sinTildes(medio);
    if (t.startsWith('efectivo')) return 'banknote';
    if (t.startsWith('debito') || t.startsWith('tarjeta') || t.startsWith('credito')) return 'credit-card';
    if (t.startsWith('transferencia')) return 'landmark';
    if (t.startsWith('qr')) return 'qr-code';
    return null;
  }

  function medio(texto, clase) {
    const icono = nombreIconoMedio(texto);
    return span(`medio ${clase || ''}`.trim(), icono ? svg(icono) : '', texto);
  }

  /** Para Galicia, Mercado Pago, etc.: sin logos, un círculo de color con la inicial. */
  function circuloInicial(nombre, color) {
    const el = span('inicial', (nombre || '?').trim().charAt(0).toUpperCase());
    if (color) el.style.background = color;
    return el;
  }

  const ICONO_ORIGEN = { TEXTO: 'message-square', AUDIO: 'mic', TICKET: 'receipt' };
  const iconoOrigen = (origen) => svg(ICONO_ORIGEN[origen] || 'message-square');

  // --- Estados: ícono + color + texto, para que no dependa solo del color ---

  function estado(tipo, ...texto) {
    const icono = { ok: 'circle-check', falta: 'clock', neutro: 'circle-minus', aviso: 'triangle-alert', info: 'info' }[tipo];
    return span(`estado ${tipo}`, svg(icono), span('', ...texto));
  }

  // --- Estados vacíos y esqueletos de carga ---

  function vacio(icono, frase, ayuda) {
    const el = document.createElement('div');
    el.className = 'vacio-estado';
    const textos = document.createElement('div');
    textos.append(span('frase', frase));
    if (ayuda) textos.append(document.createElement('br'), span('note', ayuda));
    el.append(svg(icono), textos);
    return el;
  }

  function esqueleto(filas = 3) {
    return Array.from({ length: filas }, () => span('esqueleto'));
  }

  // --- Avisos cortos ---

  /**
   * @param accion opcional: { texto, alTocar, ms } — un botón en el aviso (por ejemplo "Deshacer"), que queda
   *               ms milisegundos en pantalla. Tocarlo cierra el aviso. Solo { ms }: un aviso más largo, sin botón.
   */
  function toast(mensaje, tipo = 'ok', accion = null) {
    let caja = document.getElementById('toasts');
    if (!caja) {
      caja = document.createElement('div');
      caja.id = 'toasts';
      caja.setAttribute('role', 'status');
      caja.setAttribute('aria-live', 'polite');
      document.body.append(caja);
    }
    const el = span(`toast ${tipo}`, svg(tipo === 'ok' ? 'circle-check' : 'circle-alert'), span('', mensaje));
    const dura = accion && accion.ms ? accion.ms : 3600;
    const cerrar = () => { el.classList.add('sale'); setTimeout(() => el.remove(), 400); };
    if (accion && accion.texto) {
      const boton = document.createElement('button');
      boton.type = 'button';
      boton.className = 'toast-accion';
      boton.append(svg('undo-2'), accion.texto);
      boton.addEventListener('click', () => { cerrar(); accion.alTocar(); }, { once: true });
      el.append(boton);
    }
    caja.append(el);
    setTimeout(cerrar, dura - 400);
  }

  window.Iconos = {
    svg, cargarCategorias, categoriaPorNombre, categoriaPorEmoji, iconoCategoria, etiquetaCategoria,
    medio, circuloInicial, iconoOrigen, estado, vacio, esqueleto, toast, colorDe,
  };
})();
