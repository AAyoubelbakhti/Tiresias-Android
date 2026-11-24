package com.xebolax.tiresias.nucleo

/**
 * Niveles de puntuación, como en NVDA y en el Tiresias de Windows. Cada símbolo
 * tiene el nivel a partir del cual se dice: con [ALGUNAS] se dicen los de nivel
 * [ALGUNAS]; con [TODAS], todos. [CARACTER] solo se dice al leer carácter a
 * carácter.
 *
 * Es independiente del motor de voz (una de las críticas a TalkBack es que solo
 * funciona con la voz de Google).
 */
enum class NivelPuntuacion { NINGUNA, ALGUNAS, MAYORIA, TODAS, CARACTER }

/** Un símbolo y cómo se dice. [mantener]: si además del nombre se deja el signo (para la entonación). */
data class Simbolo(val simbolo: String, val nombre: String, val nivel: NivelPuntuacion, val mantener: Boolean = false)

/**
 * La tabla de símbolos de un idioma. Se carga de `assets/idiomas/<código>/simbolos.tsv`
 * (y se puede ampliar con la del usuario):
 *
 * ```
 * # símbolo<TAB>nombre<TAB>nivel[<TAB>mantener]
 * ¿	abre interrogación	mayoria
 * ?	cierra interrogación	mayoria	mantener
 * ```
 *
 * Los niveles se escriben `ninguna`, `algunas`, `mayoria`, `todas` o `caracter`.
 * `\t` se escribe `\\t` y el espacio `\\s` en la primera columna.
 */
class TablaSimbolos(simbolos: Collection<Simbolo>) {
	private val porSimbolo: Map<String, Simbolo> = simbolos.associateBy { it.simbolo }
	/** Los de más de un carácter primero, para que «...» gane a «.». */
	private val ordenados = simbolos.sortedByDescending { it.simbolo.length }
	private val maximo = simbolos.maxOfOrNull { it.simbolo.length } ?: 1

	operator fun get(simbolo: String): Simbolo? = porSimbolo[simbolo]

	val todos: Collection<Simbolo> get() = porSimbolo.values

	/** Una tabla con los símbolos de [otra] por encima de los de esta (los del usuario mandan). */
	fun mas(otra: TablaSimbolos): TablaSimbolos = TablaSimbolos((porSimbolo + otra.porSimbolo).values)

	/**
	 * Sustituye en [texto] los símbolos cuyo nivel entra en [nivel] por su nombre
	 * entre espacios. Las letras, los dígitos y los espacios no se tocan.
	 *
	 * Con [NivelPuntuacion.NINGUNA], los signos que no se dicen no llegan al motor
	 * (que los leería a su manera: «arroba», «asterisco»): van como un espacio,
	 * salvo los que hacen falta para entender el texto ([callado]). Con [sentido],
	 * también se dejan los que cambian lo que se dice: «−5», «20 %», «3 €», «2 = 2».
	 */
	fun aplicar(texto: String, nivel: NivelPuntuacion, sentido: Boolean = false): String {
		if (nivel != NivelPuntuacion.NINGUNA) return aplicarYa(texto, nivel, sentido, callar = false)
		val r = aplicarYa(texto, nivel, sentido, callar = true)
		// Un texto que solo era signos («...», «—») no se queda en nada: el motor lo dice a su manera
		return if (r.isBlank() && texto.isNotBlank()) aplicarYa(texto, nivel, sentido, callar = false) else r
	}

	private fun aplicarYa(texto: String, nivel: NivelPuntuacion, sentido: Boolean, callar: Boolean): String {
		// Un carácter suelto (al leer por caracteres) no se calla nunca
		val suelto = texto.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }
		val silenciar = callar && !suelto && nivel == NivelPuntuacion.NINGUNA
		if (!silenciar && nivel == NivelPuntuacion.NINGUNA && ordenados.none { it.nivel == NivelPuntuacion.NINGUNA }) return texto
		val sb = StringBuilder(texto.length + 16)
		var i = 0
		while (i < texto.length) {
			val c = texto[i]
			if (c.isLetterOrDigit() || c == ' ') {
				sb.append(c)
				i++
				continue
			}
			var encontrado: Simbolo? = null
			var largo = minOf(maximo, texto.length - i)
			while (largo >= 1) {
				val s = porSimbolo[texto.substring(i, i + largo)]
				if (s != null) {
					encontrado = s
					break
				}
				largo--
			}
			if (encontrado != null && dice(encontrado.nivel, nivel)) {
				// Puntos decimales y de miles entre cifras: no son puntuación
				if (esSeparadorNumerico(texto, i, encontrado)) {
					sb.append(c)
					i++
					continue
				}
				sb.append(' ').append(encontrado.nombre).append(' ')
				if (encontrado.mantener) sb.append(encontrado.simbolo)
				i += encontrado.simbolo.length
			} else if (silenciar) {
				// Uno a uno, no el símbolo entero de la tabla: en «hola...» el primer punto se queda y los otros, no
				val largo1 = Character.charCount(texto.codePointAt(i))
				if (callado(texto, i, sentido, encontrado != null)) sb.append(' ') else sb.append(texto, i, i + largo1)
				i += largo1
			} else if (encontrado != null) {
				sb.append(texto, i, i + encontrado.simbolo.length)
				i += encontrado.simbolo.length
			} else {
				sb.append(c)
				i++
			}
		}
		return sb.toString()
	}

	/** El nombre de un carácter suelto (lectura por carácter): siempre se dice, sea del nivel que sea. */
	fun nombreCaracter(c: String): String? = porSimbolo[c]?.nombre

	private fun esSeparadorNumerico(texto: String, i: Int, s: Simbolo): Boolean {
		if (s.simbolo != "." && s.simbolo != "," && s.simbolo != ":") return false
		val antes = i > 0 && texto[i - 1].isDigit()
		val despues = i + 1 < texto.length && texto[i + 1].isDigit()
		return antes && despues
	}

	companion object {
		/** Apóstrofos y guiones que unen dos partes de una palabra o una palabra y un número («l'aigua», «e-mail», «COVID-19»). */
		private const val UNIONES = "'’ʼ-‐‑"
		/** Los que van entre cifras: decimales, miles, la hora y las fechas («3,5», «10:30», «1/2»). */
		private const val ENTRE_CIFRAS = ".,:/"
		/** Los del final de una palabra o de una frase, por la pausa y la entonación que pone el motor. */
		private const val FINALES = ".,!?;:…。、！？；：，"
		/** Los que cierran algo detrás de una palabra («"hola".», «(sí),»): el signo de después sigue siendo final. */
		private const val CIERRES = "\"'»”’)]}›"
		/** Con «los símbolos que cambian el sentido»: delante de una cifra («−5», «+34», «±2»). */
		private const val SIGNOS_NUMERO = "+-−±"
		/** Con «los símbolos que cambian el sentido»: siempre («20 %», «30 °»). */
		private const val UNIDADES = "%°‰‱"
		/** Con «los símbolos que cambian el sentido»: entre cifras, con hasta [ESPACIOS_OPERADOR] espacios a cada lado. */
		private const val OPERADORES = "+−×÷=<>≤≥≠±"
		private const val ESPACIOS_OPERADOR = 3

		/**
		 * Si el signo de [texto] en [i] se calla (va como un espacio) con la
		 * puntuación «ninguna» (SandFly 98c9e0532 y cc1bbf045). Se quedan:
		 *
		 * - los emojis (y el signo que lleva detrás el selector de emoji: «#️⃣»);
		 * - el apóstrofo y el guion entre letras o cifras («e-mail», «COVID-19»);
		 * - `. , : /` entre cifras;
		 * - `. , ! ? ; : …` al final de una palabra, por la pausa y la entonación;
		 * - lo que no es un signo ni un símbolo (marcas, controles) y los símbolos
		 *   que no están en la tabla ([enTabla] false): pueden ser pictogramas;
		 * - con [sentido], los que cambian lo que se dice: el signo delante de una
		 *   cifra, `% ° ‰ ‱`, las monedas y las operaciones entre cifras.
		 */
		fun callado(texto: String, i: Int, sentido: Boolean = false, enTabla: Boolean = true): Boolean {
			val cp = texto.codePointAt(i)
			val c = texto[i]
			val siguiente = i + Character.charCount(cp)
			if (Normalizador.esEmoji(cp)) return false
			if (siguiente < texto.length && (texto[siguiente] == '\uFE0F' || texto[siguiente] == '\u20E3')) return false
			val tipo = Character.getType(cp)
			val puntuacion = tipo == Character.CONNECTOR_PUNCTUATION.toInt() || tipo == Character.DASH_PUNCTUATION.toInt() ||
				tipo == Character.START_PUNCTUATION.toInt() || tipo == Character.END_PUNCTUATION.toInt() ||
				tipo == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || tipo == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
				tipo == Character.OTHER_PUNCTUATION.toInt()
			val simbolo = tipo == Character.MATH_SYMBOL.toInt() || tipo == Character.CURRENCY_SYMBOL.toInt() ||
				tipo == Character.MODIFIER_SYMBOL.toInt() || tipo == Character.OTHER_SYMBOL.toInt()
			if (!puntuacion && !simbolo) return false
			// Un símbolo que no está en la tabla puede ser un emoji o un pictograma que el motor sabe decir
			if (tipo == Character.OTHER_SYMBOL.toInt() && !enTabla) return false
			val antes = if (i > 0) texto.codePointBefore(i) else -1
			val despues = if (siguiente < texto.length) texto.codePointAt(siguiente) else -1
			val letraAntes = antes >= 0 && Character.isLetterOrDigit(antes)
			val letraDespues = despues >= 0 && Character.isLetterOrDigit(despues)
			if (c in UNIONES && letraAntes && letraDespues) return false
			if (c in ENTRE_CIFRAS && antes >= 0 && Character.isDigit(antes) && despues >= 0 && Character.isDigit(despues)) return false
			if (c in FINALES && finDePalabra(texto, i)) return false
			if (sentido) {
				if (c in SIGNOS_NUMERO && despues >= 0 && Character.isDigit(despues) && !letraAntes) return false
				if (c in UNIDADES || tipo == Character.CURRENCY_SYMBOL.toInt()) return false
				if (c in OPERADORES && entreCifras(texto, i, siguiente)) return false
			}
			return true
		}

		/** Si el signo final de [i] va detrás de una palabra (saltando otros finales y cierres) y no la une con otra. */
		private fun finDePalabra(texto: String, i: Int): Boolean {
			var a = i - 1
			while (a >= 0 && (texto[a] in FINALES || texto[a] in CIERRES)) a--
			if (a < 0 || !Character.isLetterOrDigit(texto.codePointBefore(a + 1))) return false
			var d = i + 1
			while (d < texto.length && (texto[d] in FINALES || texto[d] in CIERRES)) d++
			return d >= texto.length || !Character.isLetterOrDigit(texto.codePointAt(d))
		}

		/** Si el operador entre [inicio] y [fin] va entre dos cifras, con pocos espacios a cada lado («2 + 2»). */
		private fun entreCifras(texto: String, inicio: Int, fin: Int): Boolean {
			var a = inicio - 1
			var n = 0
			while (a >= 0 && texto[a] == ' ' && n < ESPACIOS_OPERADOR) {
				a--
				n++
			}
			if (a < 0 || !texto[a].isDigit()) return false
			var d = fin
			n = 0
			while (d < texto.length && texto[d] == ' ' && n < ESPACIOS_OPERADOR) {
				d++
				n++
			}
			return d < texto.length && texto[d].isDigit()
		}

		fun dice(simbolo: NivelPuntuacion, actual: NivelPuntuacion): Boolean = when (actual) {
			NivelPuntuacion.CARACTER -> true
			else -> simbolo != NivelPuntuacion.CARACTER && simbolo.ordinal <= actual.ordinal
		}

		/** Lee una tabla en formato TSV (ver la documentación de la clase). Las líneas mal formadas se saltan. */
		fun leer(tsv: String): TablaSimbolos {
			val lista = mutableListOf<Simbolo>()
			for (linea in tsv.lineSequence()) {
				// «#» y un tabulador es el símbolo de la almohadilla, no un comentario
				if (linea.isBlank() || (linea.startsWith("#") && !linea.startsWith("#\t"))) continue
				val campos = linea.split('\t')
				if (campos.size < 3) continue
				val simbolo = campos[0].replace("\\t", "\t").replace("\\s", " ").replace("\\\\", "\\")
				if (simbolo.isEmpty()) continue
				val nivel = when (campos[2].trim().lowercase()) {
					"ninguna" -> NivelPuntuacion.NINGUNA
					"algunas" -> NivelPuntuacion.ALGUNAS
					"mayoria", "mayoría" -> NivelPuntuacion.MAYORIA
					"todas" -> NivelPuntuacion.TODAS
					"caracter", "carácter" -> NivelPuntuacion.CARACTER
					else -> continue
				}
				lista += Simbolo(simbolo, campos[1].trim(), nivel, campos.getOrNull(3)?.trim() == "mantener")
			}
			return TablaSimbolos(lista)
		}
	}
}
