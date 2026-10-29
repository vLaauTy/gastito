/* Ajustes del dashboard para pantallas chicas. No calcula nada: solo presentación. */
(() => {
  'use strict';

  const chico = window.matchMedia('(max-width: 719.98px)');

  // --- Tablas → tarjetas: cada celda lleva el título de su columna (lo muestra el CSS) ---

  function etiquetar(tabla) {
    const titulos = [...tabla.querySelectorAll('thead th')].map((th) => th.textContent.trim());
    for (const fila of tabla.querySelectorAll('tbody > tr')) {
      if (fila.dataset.movil === '1') continue;
      fila.dataset.movil = '1';
      const celdas = [...fila.children];
      if (celdas.length === 1 && celdas[0].colSpan > 1) continue;
      celdas.forEach((td, i) => { if (titulos[i]) td.dataset.label = titulos[i]; });
      // Lo principal de la tarjeta: la primera celda que no es la fecha ni el mes.
      const principal = celdas.find((td) => !td.classList.contains('date') && td.dataset.label !== 'Mes');
      if (principal) principal.classList.add('principal');
    }
  }

  const observador = new MutationObserver((cambios) => {
    for (const c of cambios) {
      const tabla = c.target.closest && c.target.closest('table');
      if (tabla) etiquetar(tabla);
    }
  });

  function observar() {
    for (const tabla of document.querySelectorAll('table')) {
      etiquetar(tabla);
      const cuerpo = tabla.tBodies[0];
      if (cuerpo) observador.observe(cuerpo, { childList: true });
    }
  }

  // Tocar la tarjeta despliega el detalle (productos del ticket, conceptos del recibo) y, en un gasto, Editar y
  // Eliminar.
  document.addEventListener('click', (e) => {
    if (!chico.matches) return;
    const fila = e.target.closest('tbody > tr');
    if (!fila || e.target.closest('button, a, input, select, label')) return;
    if (fila.querySelector('td.acciones')) fila.classList.toggle('abierta');
    const boton = fila.querySelector('button.desplegar');
    if (boton && !boton.disabled) boton.click();
  });

  // --- Menú inferior: "Más" muestra las secciones que no entran ---

  function menuMas() {
    const mas = document.getElementById('nav-mas');
    const nav = document.querySelector('nav');
    if (!mas || !nav) return;
    const cerrar = () => { nav.classList.remove('mas-abierto'); mas.setAttribute('aria-expanded', 'false'); };
    mas.addEventListener('click', () => {
      const abierto = nav.classList.toggle('mas-abierto');
      mas.setAttribute('aria-expanded', String(abierto));
    });
    nav.addEventListener('click', (e) => { if (e.target.closest('a[data-view]')) cerrar(); });
    document.addEventListener('click', (e) => { if (!e.target.closest('nav')) cerrar(); });
    window.addEventListener('hashchange', cerrar);
    new MutationObserver(() => {
      mas.classList.toggle('active', !!nav.querySelector('a.en-mas.active'));
    }).observe(nav, { subtree: true, attributes: true, attributeFilter: ['class'] });
  }

  document.addEventListener('DOMContentLoaded', () => { observar(); menuMas(); });
})();
