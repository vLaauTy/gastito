# Pedido para Claude Code: ajustes de la Etapa 1

Pegale a Claude Code todo lo que está debajo de la línea.

---

Probé el bot y hay dos ajustes antes de seguir. Leé `CLAUDE.md`, armá un plan y mostrámelo antes de tocar código. Recordá: todavía no tengo Docker, todo tiene que correr y testearse con H2. Los cambios de base de datos van en una migración nueva (`V2__...`), no edites la V1.

## 1. La categoría no se adivina: si hay duda, me pregunta con botones

Problema: mandé `3700 en pan de pancho y papas, efectivo` y lo clasificó como Salidas. Podría ser Kiosco o Supermercado, y el bot eligió solo, mal.

Qué quiero:

- **Descripción por categoría.** Agregá una columna `descripcion` a `categoria` (migración V2) y pasásela a la IA en el prompt, para que sepa qué entra en cada una:
  - Kiosco: golosinas, bebidas, cigarrillos, snacks, compras chicas al paso.
  - Supermercado: comida y productos de almacén para la casa (pan, fiambre, verdura, carne), supermercados, almacenes, autoservicios.
  - Salidas: bares, boliches, cine, eventos, entradas, salidas con amigos fuera de casa.
  - Comida afuera / delivery: comida lista en restaurantes, rotiserías o pedida por app.
  - Productos para la casa: limpieza, artículos del hogar, ferretería.
  - Servicios: luz, gas, agua, internet, celular, servicios sociales.
  - Transporte / Moto: nafta, colectivo, SUBE, Uber, repuestos y service de la moto, peajes.
  - Suscripciones: streaming, apps, servicios digitales mensuales.
  - Seguros: seguro de la moto y otros seguros.
  - Salud / farmacia: farmacia, médicos, análisis.
  - Ropa: ropa y calzado.
  - Impuestos y cargos de tarjeta: impuesto de sellos, IVA, percepciones, intereses, comisiones.
  - Otros: lo que no encaja en ninguna.
- **La IA devuelve hasta 3 categorías posibles, ordenadas, y un nivel de confianza** (`alta`, `media`, `baja`), en lugar de una sola.
  - Confianza `alta`: la tarjeta de confirmación viene con la categoría elegida.
  - Confianza `media` o `baja`: la tarjeta muestra **¿Qué categoría?** con un botón por cada candidata y un botón **Otra…** que abre todas las categorías en botones.
  - Hasta que no haya categoría elegida, el botón Guardar no aparece.
- **Que aprenda de mí.** Cuando confirmo un gasto, se guarda qué categoría le di a esa descripción. Antes de llamar a la IA, buscá si ya confirmé gastos con una descripción igual o muy parecida (normalizada: sin tildes, minúsculas). Si los hay y siempre fueron a la misma categoría, usala con confianza alta. Además, pasale a la IA en el prompt mis últimas 20 confirmaciones como ejemplos.
- Tests: categorías candidatas y confianza, el flujo de elegir categoría con botones, y el aprendizaje desde el historial.

## 2. Que el bot se vea más como una app

Hoy el bot manda una línea de texto plano. Quiero que se acerque al prototipo:

- **Tarjeta de confirmación con formato** (parse mode HTML), un dato por línea, el monto destacado:

  ```
  🧾 Nuevo gasto
  💰 $3.700
  📝 Pan de pancho y papas
  🏷 ¿Qué categoría?
  💳 Efectivo
  📅 Hoy 16:42
  ```

  Cada categoría tiene un emoji propio: agregá una columna `emoji` a `categoria` en la V2 y usalo en la tarjeta y en los resúmenes.
- **Editar con botones por campo**, no reescribiendo todo el mensaje. Al tocar Editar aparecen: [Categoría] [Monto] [Medio de pago] [Fecha] [Descripción]. Categoría y medio de pago se eligen con botones; monto, fecha y descripción me piden que escriba el valor nuevo.
- **Editar el mismo mensaje en lugar de mandar uno nuevo.** Al tocar Guardar, la tarjeta se transforma en `✅ Guardado`, con el total del mes en esa categoría. Al tocar Cancelar, en `❌ Cancelado`. Al elegir una categoría, la tarjeta se actualiza en el lugar. El chat tiene que quedar limpio: un mensaje por gasto.
- **Menú fijo de botones abajo** (reply keyboard), siempre visible: `📊 Hoy` · `📅 Mes` · `💸 Costos IA` · `❓ Ayuda`. Y registrá los comandos con `setMyCommands` para que aparezcan en el botón Menú de Telegram.
- **`/mes` más visual:** total del mes arriba, y debajo cada categoría con su emoji, monto y una barra de texto proporcional (por ejemplo con `▇`) dentro de un bloque monoespaciado para que las barras queden alineadas.

El panel con gráficos del prototipo es la etapa 5 (Mini App). En esta etapa solo mejoramos lo que se puede hacer dentro del chat.

## Al terminar

Corré los tests, levantá la aplicación y decime cómo probar: un gasto con categoría clara (`kiosco 1500`), uno dudoso (`3700 en pan de pancho y papas, efectivo`) y la edición de un campo. Commits chicos por cada cambio.
