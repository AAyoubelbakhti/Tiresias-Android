package com.xebolax.tiresias.nucleo.braille

/**
 * Las líneas braille Seika (Nippon Telesoft): las líneas de 40 y 80 celdas
 * (Seika versión 3, 4, 5 y Seika80) y los anotadores (Seika Mini). No siguen la
 * norma HID Braille: van por un chip de Silicon Labs que hace de puerto serie
 * (CP210x en las líneas, CP2110 en los anotadores) a 9600 baudios, y cada una
 * habla uno de estos tres protocolos. Sacado del controlador Seika de BRLTTY
 * (`Drivers/Braille/Seika/braille.c`, LGPL 2.1, en `adjuntos`), reescrito aquí.
 */
enum class ProtocoloSeika {
	/** Seika Mini y los anotadores: teclado braille, dos botones y dos palancas. */
	ANOTADOR,
	/** Las líneas Seika de 40 y 80 celdas: ocho teclas (K1 a K8) y encaminamiento. */
	LINEA,
	/** Las líneas Seika que imitan a una PowerBraille (las mismas teclas). */
	POWERBRAILLE;

	/** Lo que se manda para que la línea diga quién es. */
	fun pedirIdentidad(): ByteArray = when (this) {
		ANOTADOR -> bytes(0xFF, 0xFF, 0xA1)
		LINEA -> bytes(0xFF, 0xFF, 0x1C)
		POWERBRAILLE -> bytes(0xFF, 0xFF, 0x0A)
	}

	/** El paquete que pone [contenido] en una línea de [n] celdas (puntos ISO 11548-1, el 1 en el bit 0). */
	fun celdas(n: Int, contenido: ByteArray): ByteArray {
		fun celda(i: Int): Byte = contenido.getOrElse(i) { 0 }
		return when (this) {
			ANOTADOR -> ByteArray(4 + n).also { p ->
				p[0] = 0xFF.toByte(); p[1] = 0xFF.toByte(); p[2] = 0xA3.toByte(); p[3] = n.toByte()
				for (i in 0 until n) p[4 + i] = celda(i)
			}
			LINEA -> if (n == 80) {
				val cabecera = bytes(0xFF, 0xFF, 0x73, 0x38, 0x30, 0x00, 0x00, 0x00)
				ByteArray(cabecera.size + n).also { p ->
					cabecera.copyInto(p)
					for (i in 0 until n) p[cabecera.size + i] = celda(i)
				}
			} else {
				// Las de 40: cada celda va detrás de un cero
				val cabecera = bytes(0xFF, 0xFF, 0x73, 0x65, 0x69, 0x6B, 0x61, 0x00)
				ByteArray(cabecera.size + 2 * n).also { p ->
					cabecera.copyInto(p)
					for (i in 0 until n) p[cabecera.size + 2 * i + 1] = celda(i)
				}
			}
			POWERBRAILLE -> {
				val cabecera = bytes(0xFF, 0xFF, 0x04, 0x00, 0x63, 0x00, 2 * n, 0x00)
				ByteArray(cabecera.size + 2 * n).also { p ->
					cabecera.copyInto(p)
					for (i in 0 until n) p[cabecera.size + 2 * i + 1] = celda(i)
				}
			}
		}
	}

	companion object {
		internal fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
	}
}

/** Lo que llega de una línea Seika. */
sealed interface PaqueteSeika {
	/** Quién es: sus celdas, cuántas teclas tiene y cuántas de encaminamiento. */
	data class Identidad(val celdas: Int, val teclas: Int, val encaminamiento: Int) : PaqueteSeika
	/** Una combinación de teclas (cada bit, una tecla; ver [TeclasSeika]) y las de encaminamiento pulsadas. */
	data class Teclas(val teclas: Long, val encaminamiento: Set<Int>) : PaqueteSeika
}

/**
 * Junta los bytes que llegan de una línea Seika y los separa en paquetes. Los
 * bytes que no forman ningún paquete se tiran de uno en uno hasta volver a
 * encontrar uno (como en BRLTTY). Las celdas las sabe al llegar la identidad.
 */
class LectorSeika(val protocolo: ProtocoloSeika) {
	private val pendiente = ArrayList<Byte>()

	/** Las celdas y teclas de la línea, cuando ya se sabe quién es. */
	var celdas = 0
		private set
	private var numTeclas = 0

	/** Recibe [n] bytes de [b] y devuelve los paquetes completos que hay. */
	fun recibir(b: ByteArray, n: Int = b.size): List<PaqueteSeika> {
		for (i in 0 until n) pendiente += b[i]
		val paquetes = ArrayList<PaqueteSeika>()
		while (pendiente.isNotEmpty()) {
			val (estado, largo) = if (protocolo == ProtocoloSeika.ANOTADOR) medirAnotador() else medirLinea()
			when (estado) {
				Estado.ESPERAR -> break
				Estado.NADA -> pendiente.removeAt(0)
				Estado.COMPLETO -> {
					val p = ByteArray(largo) { pendiente[it] }
					repeat(largo) { pendiente.removeAt(0) }
					leer(p)?.let { paquetes += it }
				}
			}
		}
		// Por si llega basura sin parar: nunca se guarda más de un paquete largo
		if (pendiente.size > MAXIMO) pendiente.clear()
		return paquetes
	}

	private enum class Estado { COMPLETO, ESPERAR, NADA }

	private fun u(i: Int): Int = pendiente[i].toInt() and 0xFF

	// --- Anotadores: FF FF tipo largo datos... -----------------------------------------------

	private fun medirAnotador(): Pair<Estado, Int> {
		if (u(0) != 0xFF) return Estado.NADA to 0
		if (pendiente.size < 2) return Estado.ESPERAR to 0
		if (u(1) != 0xFF) return Estado.NADA to 0
		if (pendiente.size < 4) return Estado.ESPERAR to 0
		val largo = 4 + u(3)
		return if (pendiente.size < largo) Estado.ESPERAR to 0 else Estado.COMPLETO to largo
	}

	// --- Líneas: plantillas de bytes (identidad, encaminamiento y teclas) --------------------

	private fun medirLinea(): Pair<Estado, Int> {
		var esperar = false
		for (p in plantillas()) {
			when (encaja(p)) {
				Estado.COMPLETO -> return Estado.COMPLETO to p.size
				Estado.ESPERAR -> esperar = true
				Estado.NADA -> Unit
			}
		}
		return (if (esperar) Estado.ESPERAR else Estado.NADA) to 0
	}

	private fun plantillas(): List<IntArray> {
		val lista = ArrayList<IntArray>(3)
		lista += if (protocolo == ProtocoloSeika.LINEA) IDENTIDAD_LINEA else IDENTIDAD_POWERBRAILLE
		when (celdas) {
			40 -> lista += ENCAMINAMIENTO_40
			80 -> lista += ENCAMINAMIENTO_80
		}
		lista += TECLAS
		return lista
	}

	private fun encaja(p: IntArray): Estado {
		val n = minOf(p.size, pendiente.size)
		for (i in 0 until n) {
			val b = u(i)
			val ok = when (val t = p[i]) {
				CUALQUIERA -> true
				CIFRA -> b in '0'.code..'9'.code
				TAMANO -> b == 40 || b == 80
				ID1 -> b.toChar() in "3458"
				ID2 -> b.toChar() in "0 "
				TECLA1 -> b and 0xE0 == 0x60
				TECLA2 -> b and 0xE0 == 0xE0
				else -> b == t
			}
			if (!ok) return Estado.NADA
		}
		return if (pendiente.size >= p.size) Estado.COMPLETO else Estado.ESPERAR
	}

	// --- Lo que dice cada paquete ----------------------------------------------------------------

	private fun leer(p: ByteArray): PaqueteSeika? {
		fun b(i: Int) = p[i].toInt() and 0xFF
		if (protocolo == ProtocoloSeika.ANOTADOR) {
			return when (b(2)) {
				0xA2 -> PaqueteSeika.Identidad(b(5), b(4), b(6)).also { celdas = it.celdas; numTeclas = it.teclas }
				0xA4 -> PaqueteSeika.Teclas(0, encaminamiento(p, 4, p.size))
				0xA6 -> PaqueteSeika.Teclas(pocoAPoco(p, 4, p.size), emptySet())
				0xA8 -> {
					val fin = minOf(p.size, 4 + (numTeclas + 7) / 8)
					PaqueteSeika.Teclas(pocoAPoco(p, 4, fin), encaminamiento(p, fin, p.size))
				}
				else -> null
			}
		}
		return when {
			p.size == 2 -> PaqueteSeika.Teclas(((b(1) and 0x1F) shl 8 or (b(0) and 0x1F)).toLong(), emptySet())
			// La identidad de las líneas: «seika» y una cifra (8 es la de 80 celdas)
			protocolo == ProtocoloSeika.LINEA && b(0) == 0x73 -> {
				val n = if (b(5) == '8'.code) 80 else 40
				PaqueteSeika.Identidad(n, 16, n).also { celdas = n; numTeclas = 16 }
			}
			protocolo == ProtocoloSeika.POWERBRAILLE && p.size == IDENTIDAD_POWERBRAILLE.size && b(1) == 0x05 -> {
				val n = b(2)
				PaqueteSeika.Identidad(n, 16, n).also { celdas = n; numTeclas = 16 }
			}
			else -> PaqueteSeika.Teclas(0, encaminamiento(p, 7, 7 + celdas / 8))
		}
	}

	/** Bytes en orden de menor a mayor (el primero, las teclas 0 a 7). */
	private fun pocoAPoco(p: ByteArray, desde: Int, hasta: Int): Long {
		var v = 0L
		for (i in (hasta - 1) downTo desde) v = (v shl 8) or (p[i].toLong() and 0xFF)
		return v
	}

	private fun encaminamiento(p: ByteArray, desde: Int, hasta: Int): Set<Int> {
		val pulsadas = HashSet<Int>()
		for (i in desde until minOf(hasta, p.size)) {
			val b = p[i].toInt() and 0xFF
			for (bit in 0..7) if (b and (1 shl bit) != 0) pulsadas += (i - desde) * 8 + bit
		}
		return pulsadas
	}

	companion object {
		private const val MAXIMO = 4 + 0xFF
		// Marcas de las plantillas (fuera de 0..255)
		private const val CUALQUIERA = 0x100
		private const val CIFRA = 0x101
		private const val TAMANO = 0x102
		private const val ID1 = 0x103
		private const val ID2 = 0x104
		private const val TECLA1 = 0x105
		private const val TECLA2 = 0x106

		/** «seika» + modelo + «v» + versión («seika80v1.02», «seika3 v2.10»...). */
		private val IDENTIDAD_LINEA = intArrayOf(0x73, 0x65, 0x69, 0x6B, 0x61, ID1, ID2, 0x76, CIFRA, 0x2E, CIFRA, CIFRA)
		private val IDENTIDAD_POWERBRAILLE = intArrayOf(0x00, 0x05, TAMANO, 0x08, CUALQUIERA, CUALQUIERA, CUALQUIERA, CUALQUIERA, CUALQUIERA, CUALQUIERA, CUALQUIERA, CUALQUIERA)
		private val ENCAMINAMIENTO_40 = intArrayOf(0x00, 0x08, 0x09, 0, 0, 0, 0) + IntArray(5) { CUALQUIERA } + intArrayOf(0x00, 0x08, 0x09, 0, 0, 0, 0, 0, 0, 0, 0, 0)
		private val ENCAMINAMIENTO_80 = intArrayOf(0x00, 0x08, 0x0F, 0, 0, 0, 0) + IntArray(10) { CUALQUIERA } + intArrayOf(0, 0, 0, 0, 0)
		private val TECLAS = intArrayOf(TECLA1, TECLA2)
	}
}

/**
 * Las teclas de las Seika como teclas de línea de Tiresias ([TeclaLinea]). Los
 * puntos, el espacio, los botones de desplazar y la palanca izquierda del
 * anotador son los usos HID de siempre ([UsosBraille]), así que valen las
 * combinaciones de espacio y puntos de cualquier línea; las demás son usos
 * propios ([UsosSeika]) con lo que hacen en [TecladoLinea.PROPIAS].
 */
object TeclasSeika {
	/** Bits de las teclas de las líneas (BRLTTY, `brldefs-sk.h`). */
	private val LINEA = mapOf(
		0 to UsosSeika.K1, 1 to UsosSeika.K7, 2 to UsosSeika.K8, 3 to UsosSeika.K6,
		4 to UsosSeika.K5, 9 to UsosSeika.K2, 11 to UsosSeika.K3, 12 to UsosSeika.K4,
	)

	/** Bits de las teclas de los anotadores, del 8 en adelante (del 0 al 7 son los puntos). */
	private val ANOTADOR = mapOf(
		8 to UsosSeika.RETROCESO,
		9 to UsosBraille.ESPACIO,
		10 to UsosBraille.DESPLAZAR_IZQUIERDA,
		11 to UsosBraille.DESPLAZAR_DERECHA,
		12 to UsosBraille.JOYSTICK_CENTRO,
		13 to UsosBraille.JOYSTICK_IZQUIERDA,
		14 to UsosBraille.JOYSTICK_DERECHA,
		15 to UsosBraille.JOYSTICK_ARRIBA,
		16 to UsosBraille.JOYSTICK_ABAJO,
		17 to UsosSeika.DERECHA_CENTRO,
		18 to UsosSeika.DERECHA_IZQUIERDA,
		19 to UsosSeika.DERECHA_DERECHA,
		20 to UsosSeika.DERECHA_ARRIBA,
		21 to UsosSeika.DERECHA_ABAJO,
	)

	fun teclas(protocolo: ProtocoloSeika, p: PaqueteSeika.Teclas): Set<TeclaLinea> {
		val t = HashSet<TeclaLinea>()
		for (bit in 0 until 32) {
			if (p.teclas and (1L shl bit) == 0L) continue
			val uso = if (protocolo == ProtocoloSeika.ANOTADOR) {
				if (bit < 8) UsosBraille.PUNTO_1 + bit else ANOTADOR[bit]
			} else LINEA[bit]
			if (uso != null) t += TeclaLinea(uso)
		}
		for (c in p.encaminamiento) t += TeclaLinea(UsosBraille.ENCAMINAMIENTO, c)
		return t
	}
}

/** Teclas de las Seika que no tienen uso en la norma HID Braille (página propia de Tiresias). */
object UsosSeika {
	private const val PAGINA = 0xFF5E
	private fun u(id: Int) = (PAGINA shl 16) or id

	val K1 = u(1)
	val K2 = u(2)
	val K3 = u(3)
	val K4 = u(4)
	val K5 = u(5)
	val K6 = u(6)
	val K7 = u(7)
	val K8 = u(8)
	/** La tecla de borrar del anotador, junto al espacio: sola borra; con puntos, hace de espacio. */
	val RETROCESO = u(0x10)
	/** La palanca derecha del anotador (la izquierda es la palanca HID). */
	val DERECHA_CENTRO = u(0x20)
	val DERECHA_ARRIBA = u(0x21)
	val DERECHA_ABAJO = u(0x22)
	val DERECHA_IZQUIERDA = u(0x23)
	val DERECHA_DERECHA = u(0x24)
}
