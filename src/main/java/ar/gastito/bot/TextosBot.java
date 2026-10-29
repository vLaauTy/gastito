package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

/** Los textos fijos del bot: la bienvenida, la ayuda y el "Escuché" arriba de lo que se entendió de un audio. */
final class TextosBot {

    static final String BIENVENIDA = """
            ¡Hola! Soy <b>Gastito</b> 👋
            Contame lo que quieras como si me escribieras por chat, o mandame un audio 🎙:

            • kiosco 1500
            • pagué 12000 de nafta con la tarjeta
            • cobré el sueldo, 980 mil en Galicia
            • agregá un fijo: el seguro de la moto, unos 32 mil por mes
            • ¿cuánto gasté en el súper este mes?

            También podés mandarme la foto del ticket 🧾. Te muestro lo que entendí y lo guardás con un toque.""";

    static final String AYUDA = """
            Decime las cosas como te salgan, por escrito o en un audio 🎙. Si me falta un dato te lo pregunto con botones, y si no estoy seguro de qué querés, te doy opciones.

            <b>🧾 Gastos</b>
            • "kiosco 1500", "ayer 3700 en pan y papas con QR"
            • "pagué 48000 el asado, éramos 6": en tus gastos cuenta solo tu parte
            • "zapatillas 84 lucas en 6 cuotas con la tarjeta"
            • La foto del ticket: leo el comercio, los productos y el total

            <b>💰 Ingresos</b>
            • "cobré el sueldo, 980 mil en Galicia", "cobré el aguinaldo"
            • "me pagaron 25 mil por arreglar una PC"
            • "Pablo me pasó 8000 del asado" (te devuelven plata de una juntada)

            <b>📌 Gastos fijos</b>
            • "agregá un fijo: el seguro de la moto, unos 32 mil por mes con la tarjeta"
            • "de luz pagué 87.500", "ya pagué el seguro"
            • "¿qué fijos me faltan pagar?", "sacá Netflix de los fijos"

            <b>✏️ Corregir</b>
            • "el último era 3500, no 3700", "lo de recién fue con débito"
            • "borrá lo último que cargué"
            • "cancelá las cuotas de las zapatillas", "lo de Pablo dalo por perdido"

            <b>🧮 Sueldo</b>
            • "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas": calculo el sueldo del mes
            • "¿cuánto me da el sueldo calculado?"
            • El PDF o la foto del recibo: lo comparo con tu cálculo y con lo que cobraste

            <b>❓ Preguntas y listas</b>
            • "¿cuánto gasté en kiosco este mes?", "compará octubre con septiembre"
            • "¿quién me debe plata?", "¿qué cuotas tengo?", "¿cuánto llevo de IA este mes?"
            • "mandame el resumen de la semana" (o del mes), "pasame el link del dashboard"
            Los números los calculo con tu base, no los inventa la IA.

            <b>📬 Lo que te mando solo</b>
            El domingo a las 21 te mando cómo fue tu semana y el día 1 a las 9, cómo fue el mes. Si la PC estaba apagada, te llega apenas la prendés.

            <b>Atajos</b> (si preferís comandos)
            /hoy · /mes · /disponible · /resumen · /juntadas · /fijos · /cuotas · /calculo · /costos · /dashboard""";

    private TextosBot() {
    }

    /** Arriba de lo que se entendió de un audio, para que se vea qué escuchó Whisper. */
    static String escuche(String texto) {
        return "🎙 Escuché: \"" + html(texto) + "\"\n\n";
    }
}
