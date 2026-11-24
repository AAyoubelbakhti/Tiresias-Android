package com.xebolax.tiresias.traduccion

import java.io.File
import java.text.Normalizer

/**
 * El tokenizador de los modelos OPUS-MT (Marian): SentencePiece de tipo
 * Unigram, leído del `tokenizer.json` de Hugging Face y hecho a mano, sin
 * bibliotecas ni servicios de Google.
 *
 * Hace lo mismo que la biblioteca `tokenizers` con ese archivo:
 * 1. Normaliza el texto (NFKC, como el SentencePiece con el que se entrenó Marian).
 * 2. Lo parte por los espacios y pone «▁» delante de cada palabra (Metaspace).
 * 3. Parte cada palabra en las piezas del vocabulario que más puntúan juntas
 *    (Viterbi). Un carácter que no está en el vocabulario es «desconocido»,
 *    con un castigo de 10 bajo la peor pieza, y varios seguidos se juntan.
 * 4. Añade el fin de frase (`</s>`).
 *
 * El desconocido es el `<unk>` de los símbolos especiales (el 1 en Marian): el
 * `unk_id` del modelo convertido apunta a la coma y no se usa.
 *
 * No depende de Android: se prueba en el PC.
 */
class TokenizadorUnigram(
	private val piezas: Array<String>,
	private val puntuaciones: DoubleArray,
	val idDesconocido: Int,
	val idFin: Int,
	private val especiales: Set<Int>,
) {
	/** Pieza → su número, sin los símbolos especiales (no salen del texto). */
	private val indice = HashMap<String, Int>(piezas.size * 2).apply {
		for (i in piezas.indices) if (i !in especiales) put(piezas[i], i)
	}
	/** La pieza más larga, en caracteres (no en unidades UTF-16). */
	private val largoMaximo = piezas.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
	private val castigoDesconocido = (puntuaciones.minOrNull() ?: 0.0) - CASTIGO_DESCONOCIDO

	val tamanoVocabulario: Int get() = piezas.size

	/** Los números de las piezas de [texto], con el fin de frase al final. */
	fun codificar(texto: String): IntArray {
		val salida = ArrayList<Int>()
		for (palabra in palabras(Normalizer.normalize(texto, Normalizer.Form.NFKC))) viterbi(METAESPACIO + palabra, salida)
		salida += idFin
		return salida.toIntArray()
	}

	/** El texto de [ids], sin los símbolos especiales, con los «▁» vueltos espacios. */
	fun decodificar(ids: IntArray): String {
		val sb = StringBuilder()
		for (id in ids) {
			if (id in especiales || id == idDesconocido || id < 0 || id >= piezas.size) continue
			sb.append(piezas[id])
		}
		return sb.toString().replace(METAESPACIO, ' ').trim()
	}

	/** Las palabras de [texto], partido por cualquier espacio (también los de Unicode). */
	private fun palabras(texto: String): List<String> {
		val lista = ArrayList<String>()
		var inicio = -1
		for (i in texto.indices) {
			val c = texto[i]
			val espacio = Character.isWhitespace(c) || Character.isSpaceChar(c)
			if (espacio) {
				if (inicio >= 0) lista += texto.substring(inicio, i)
				inicio = -1
			} else if (inicio < 0) inicio = i
		}
		if (inicio >= 0) lista += texto.substring(inicio)
		return lista
	}

	/** Parte [palabra] en las piezas que más puntúan juntas y las añade a [salida]. */
	private fun viterbi(palabra: String, salida: MutableList<Int>) {
		// Dónde empieza cada carácter (los de fuera del plano básico ocupan dos unidades UTF-16)
		val n = palabra.codePointCount(0, palabra.length)
		val posicion = IntArray(n + 1)
		var p = 0
		for (k in 0 until n) {
			posicion[k] = p
			p = palabra.offsetByCodePoints(p, 1)
		}
		posicion[n] = palabra.length

		val mejor = DoubleArray(n + 1)
		val desde = IntArray(n + 1) { -1 }
		val pieza = IntArray(n + 1) { -1 }
		for (i in 0 until n) {
			// Todas las posiciones se alcanzan: cada carácter tiene su pieza o es desconocido
			if (i > 0 && desde[i] < 0) continue
			val base = mejor[i]
			var deUno = false
			val hasta = minOf(n, i + largoMaximo)
			for (j in i + 1..hasta) {
				val id = indice[palabra.substring(posicion[i], posicion[j])] ?: continue
				val candidata = base + puntuaciones[id]
				if (desde[j] < 0 || candidata > mejor[j]) {
					mejor[j] = candidata
					desde[j] = i
					pieza[j] = id
				}
				if (j == i + 1) deUno = true
			}
			if (!deUno) {
				val candidata = base + castigoDesconocido
				if (desde[i + 1] < 0 || candidata > mejor[i + 1]) {
					mejor[i + 1] = candidata
					desde[i + 1] = i
					pieza[i + 1] = idDesconocido
				}
			}
		}
		// Hacia atrás, del final al principio
		val camino = ArrayList<Int>()
		var j = n
		while (j > 0) {
			camino += pieza[j]
			j = desde[j]
		}
		for (k in camino.indices.reversed()) {
			val id = camino[k]
			// Varios desconocidos seguidos son uno solo
			if (id == idDesconocido && salida.isNotEmpty() && salida.last() == idDesconocido) continue
			salida += id
		}
	}

	companion object {
		const val METAESPACIO = '▁'
		/** Lo que se quita a la peor pieza para un carácter desconocido (como SentencePiece). */
		private const val CASTIGO_DESCONOCIDO = 10.0

		fun desdeArchivo(f: File): TokenizadorUnigram = desdeJson(f.readText(Charsets.UTF_8))

		/**
		 * Lee el `tokenizer.json`. Se lee en flujo, sin construir el árbol
		 * entero: el vocabulario son 60 000 a 80 000 pares y JSONObject tarda y
		 * gasta mucha más memoria.
		 */
		fun desdeJson(json: String): TokenizadorUnigram {
			val l = LectorJson(json)
			val piezas = ArrayList<String>()
			val puntuaciones = ArrayList<Double>()
			val especiales = HashMap<String, Int>()
			var unkModelo = -1
			var tipo = ""
			l.objeto { clave ->
				when (clave) {
					"added_tokens" -> l.lista {
						var id = -1
						var contenido = ""
						var especial = false
						l.objeto { c ->
							when (c) {
								"id" -> id = l.numero().toInt()
								"content" -> contenido = l.cadena()
								"special" -> especial = l.logico()
								else -> l.saltar()
							}
						}
						if (especial && id >= 0) especiales[contenido] = id
					}
					"model" -> l.objeto { c ->
						when (c) {
							"type" -> tipo = l.cadena()
							"unk_id" -> unkModelo = if (l.esNulo()) -1 else l.numero().toInt()
							"vocab" -> l.lista {
								var k = 0
								l.lista {
									if (k == 0) piezas += l.cadena() else if (k == 1) puntuaciones += l.numero() else l.saltar()
									k++
								}
							}
							else -> l.saltar()
						}
					}
					else -> l.saltar()
				}
			}
			if (tipo != "Unigram") throw IllegalArgumentException("El tokenizador no es Unigram: $tipo")
			if (piezas.isEmpty() || piezas.size != puntuaciones.size) throw IllegalArgumentException("Vocabulario vacío o incompleto")
			val desconocido = especiales["<unk>"] ?: piezas.indexOf("<unk>").takeIf { it >= 0 } ?: unkModelo
			val fin = especiales["</s>"] ?: piezas.indexOf("</s>").takeIf { it >= 0 } ?: 0
			return TokenizadorUnigram(piezas.toTypedArray(), puntuaciones.toDoubleArray(), desconocido, fin, especiales.values.toSet())
		}
	}
}

/**
 * Un lector de JSON en flujo, mínimo, para el `tokenizer.json`: recorre el
 * texto sin construir objetos y salta lo que no interesa.
 */
internal class LectorJson(private val s: String) {
	private var i = 0

	private fun espacios() {
		while (i < s.length && s[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++
	}

	private fun mirar(): Char {
		espacios()
		if (i >= s.length) throw IllegalArgumentException("JSON cortado")
		return s[i]
	}

	private fun esperar(c: Char) {
		if (mirar() != c) throw IllegalArgumentException("JSON: se esperaba «$c» en $i")
		i++
	}

	/** Recorre un objeto: [alClave] recibe cada clave y debe leer (o saltar) su valor. */
	fun objeto(alClave: (String) -> Unit) {
		esperar('{')
		if (mirar() == '}') {
			i++
			return
		}
		while (true) {
			val clave = cadena()
			esperar(':')
			alClave(clave)
			when (mirar()) {
				',' -> i++
				'}' -> {
					i++
					return
				}
				else -> throw IllegalArgumentException("JSON: objeto mal cerrado en $i")
			}
		}
	}

	/** Recorre una lista: [alElemento] debe leer (o saltar) cada elemento. */
	fun lista(alElemento: () -> Unit) {
		esperar('[')
		if (mirar() == ']') {
			i++
			return
		}
		while (true) {
			alElemento()
			when (mirar()) {
				',' -> i++
				']' -> {
					i++
					return
				}
				else -> throw IllegalArgumentException("JSON: lista mal cerrada en $i")
			}
		}
	}

	fun cadena(): String {
		esperar('"')
		// Lo normal: sin escapes, se corta de una vez
		val comilla = s.indexOf('"', i)
		val barra = s.indexOf('\\', i)
		if (comilla < 0) throw IllegalArgumentException("JSON: cadena sin cerrar")
		if (barra < 0 || barra > comilla) {
			val r = s.substring(i, comilla)
			i = comilla + 1
			return r
		}
		val sb = StringBuilder()
		while (true) {
			if (i >= s.length) throw IllegalArgumentException("JSON: cadena sin cerrar")
			val c = s[i++]
			when (c) {
				'"' -> return sb.toString()
				'\\' -> {
					if (i >= s.length) throw IllegalArgumentException("JSON: escape cortado")
					when (val e = s[i++]) {
						'"' -> sb.append('"')
						'\\' -> sb.append('\\')
						'/' -> sb.append('/')
						'b' -> sb.append('\b')
						'f' -> sb.append('\u000C')
						'n' -> sb.append('\n')
						'r' -> sb.append('\r')
						't' -> sb.append('\t')
						'u' -> {
							if (i + 4 > s.length) throw IllegalArgumentException("JSON: escape cortado")
							sb.append(s.substring(i, i + 4).toInt(16).toChar())
							i += 4
						}
						else -> throw IllegalArgumentException("JSON: escape no válido «$e»")
					}
				}
				else -> sb.append(c)
			}
		}
	}

	fun numero(): Double {
		espacios()
		val inicio = i
		while (i < s.length && s[i].let { it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' || it in '0'..'9' }) i++
		if (inicio == i) throw IllegalArgumentException("JSON: se esperaba un número en $i")
		return s.substring(inicio, i).toDouble()
	}

	fun logico(): Boolean {
		mirar()
		if (s.startsWith("true", i)) {
			i += 4
			return true
		}
		if (s.startsWith("false", i)) {
			i += 5
			return false
		}
		throw IllegalArgumentException("JSON: se esperaba true o false en $i")
	}

	/** Si viene un null, lo lee y devuelve true. */
	fun esNulo(): Boolean {
		if (mirar() == 'n' && s.startsWith("null", i)) {
			i += 4
			return true
		}
		return false
	}

	/** Salta un valor cualquiera. */
	fun saltar() {
		when (mirar()) {
			'{' -> objeto { saltar() }
			'[' -> lista { saltar() }
			'"' -> cadena()
			't', 'f' -> logico()
			'n' -> if (!esNulo()) throw IllegalArgumentException("JSON: valor no válido en $i")
			else -> numero()
		}
	}
}
