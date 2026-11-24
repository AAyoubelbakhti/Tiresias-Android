package com.xebolax.tiresias.traduccion

import java.util.Locale

/**
 * El idioma de un texto para elegir el traductor, entre español, inglés,
 * francés, alemán, italiano y portugués, sin servicios de Google.
 *
 * Cuenta las palabras más frecuentes de cada idioma (las que comparten varios
 * puntúan repartidas) y las letras y signos propios (ñ y ¿ del español, ß y ä
 * del alemán, ç y œ del francés, ã y õ del portugués...). Es más amplio que el
 * de la voz ([com.xebolax.tiresias.nucleo.DetectorIdioma], solo español e
 * inglés) y más atrevido: un traductor equivocado se nota enseguida, y si no
 * hay pistas devuelve null.
 *
 * No depende de Android: se prueba en el PC.
 */
object DetectorIdioma {
	val IDIOMAS = listOf("es", "en", "fr", "de", "it", "pt")

	private val PALABRAS: Map<String, Set<String>> = mapOf(
		"es" to setOf(
			"de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "las", "por", "un", "para", "con", "una", "su", "al", "lo",
			"como", "más", "pero", "sus", "le", "ya", "o", "este", "sí", "porque", "esta", "entre", "cuando", "muy", "sin",
			"sobre", "también", "me", "hasta", "hay", "donde", "quien", "desde", "todo", "nos", "durante", "todos", "uno", "les",
			"ni", "contra", "otros", "ese", "eso", "ante", "ellos", "esto", "antes", "algunos", "qué", "unos", "yo", "otro",
			"otras", "otra", "él", "es", "son", "está", "están", "fue", "ha", "han", "tiene", "puede", "hace", "aquí", "ahora",
			"bien", "gracias", "hola", "mi", "tu", "usted", "pues", "cómo", "dónde", "nuevo", "año", "años", "día", "cuenta",
			"buscar", "inicio", "ver", "hacer", "mensaje", "mensajes", "ayuda", "ajustes",
		),
		"en" to setOf(
			"the", "be", "to", "of", "and", "a", "in", "that", "have", "it", "for", "not", "on", "with", "he", "as", "you", "do",
			"at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all",
			"would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when",
			"make", "can", "like", "just", "him", "know", "take", "into", "your", "some", "could", "them", "see", "other",
			"than", "then", "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how",
			"our", "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most",
			"us", "is", "are", "was", "were", "has", "had", "been", "thanks", "hello", "please", "message", "messages",
			"search", "home", "settings", "help", "account", "you're", "it's", "don't", "i'm", "can't",
		),
		"fr" to setOf(
			"de", "la", "le", "et", "les", "des", "en", "un", "du", "une", "que", "est", "pour", "qui", "dans", "par", "plus",
			"pas", "au", "sur", "ne", "se", "ce", "il", "sont", "avec", "son", "aux", "ou", "elle", "nous", "vous", "je", "tu",
			"mais", "leur", "cette", "ces", "comme", "tout", "fait", "été", "être", "avoir", "très", "sans", "aussi", "bien",
			"où", "même", "deux", "ils", "elles", "mon", "ton", "votre", "notre", "c'est", "j'ai", "qu'il", "n'est", "d'un",
			"l'on", "merci", "bonjour", "oui", "non", "rechercher", "accueil", "paramètres", "aide", "compte", "à", "ça",
		),
		"de" to setOf(
			"der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich", "des", "auf", "für", "ist", "im", "dem", "nicht",
			"ein", "eine", "als", "auch", "es", "an", "werden", "aus", "er", "hat", "dass", "sie", "nach", "wird", "bei",
			"einer", "um", "am", "sind", "noch", "wie", "einem", "über", "einen", "so", "zum", "war", "haben", "nur", "oder",
			"aber", "vor", "zur", "bis", "mehr", "durch", "man", "sein", "wurde", "sei", "ich", "du", "wir", "ihr", "mein",
			"dein", "nein", "ja", "danke", "hallo", "bitte", "suchen", "startseite", "einstellungen", "hilfe", "konto", "heute",
		),
		"it" to setOf(
			"di", "e", "il", "la", "che", "è", "per", "un", "in", "non", "una", "sono", "mi", "si", "ho", "lo", "ma", "ha", "le",
			"con", "ti", "i", "da", "se", "come", "io", "questo", "qui", "hai", "del", "della", "dei", "delle", "nel", "nella",
			"al", "alla", "gli", "anche", "più", "tutto", "ci", "suo", "sua", "loro", "noi", "voi", "lei", "lui", "molto", "cosa",
			"perché", "quando", "dove", "essere", "stato", "fatto", "ancora", "grazie", "ciao", "buongiorno", "cerca",
			"impostazioni", "aiuto", "oggi", "sì", "questa", "quello", "quella", "sempre", "già", "allora", "dopo",
		),
		"pt" to setOf(
			"de", "a", "o", "que", "e", "do", "da", "em", "um", "para", "é", "com", "não", "uma", "os", "no", "se", "na", "por",
			"mais", "as", "dos", "como", "mas", "foi", "ao", "ele", "das", "tem", "à", "seu", "sua", "ou", "ser", "quando",
			"muito", "há", "nos", "já", "está", "eu", "também", "só", "pelo", "pela", "até", "isso", "ela", "entre", "era",
			"depois", "sem", "mesmo", "aos", "ter", "seus", "quem", "nas", "me", "esse", "eles", "estão", "você", "tinha",
			"foram", "essa", "num", "nem", "suas", "meu", "às", "minha", "têm", "numa", "pelos", "obrigado", "obrigada", "olá",
			"pesquisar", "início", "configurações", "ajuda", "conta", "hoje", "então", "isto", "aqui", "agora",
		),
	)

	/** Letras y signos que solo (o casi solo) usa un idioma, con su peso. */
	private val LETRAS: Map<Char, List<Pair<String, Double>>> = mapOf(
		'ñ' to listOf("es" to 2.0), '¿' to listOf("es" to 2.0), '¡' to listOf("es" to 2.0),
		'á' to listOf("es" to 0.7, "pt" to 0.5), 'í' to listOf("es" to 0.7, "pt" to 0.5), 'ó' to listOf("es" to 0.7, "pt" to 0.4),
		'ú' to listOf("es" to 0.7, "pt" to 0.3),
		'ß' to listOf("de" to 2.0), 'ä' to listOf("de" to 1.5), 'ö' to listOf("de" to 1.5), 'ü' to listOf("de" to 1.2),
		'œ' to listOf("fr" to 2.0), 'ç' to listOf("fr" to 0.8, "pt" to 0.8), 'è' to listOf("fr" to 0.8, "it" to 0.6),
		'ê' to listOf("fr" to 0.8, "pt" to 0.6), 'â' to listOf("fr" to 0.6, "pt" to 0.6), 'û' to listOf("fr" to 1.0),
		'ë' to listOf("fr" to 0.8), 'ï' to listOf("fr" to 0.8), 'î' to listOf("fr" to 0.8), 'ù' to listOf("fr" to 0.6, "it" to 0.6),
		'ì' to listOf("it" to 1.5), 'ò' to listOf("it" to 1.5),
		'ã' to listOf("pt" to 2.0), 'õ' to listOf("pt" to 2.0), 'ô' to listOf("pt" to 0.8, "fr" to 0.6),
	)

	/** Cuántas veces puntúan como mucho las letras de un idioma (un texto con muchas eñes no es más español). */
	private const val MAXIMO_LETRAS = 4.0
	/** Lo que se mira de un texto largo. */
	private const val MAXIMO_CARACTERES = 3000

	private val PALABRA = Regex("[\\p{L}']+")

	/** Cuántos idiomas tienen cada palabra, para repartir su punto. */
	private val repartos: Map<String, Int> = HashMap<String, Int>().apply {
		for (lista in PALABRAS.values) for (p in lista) merge(p, 1, Int::plus)
	}

	/** La puntuación de cada idioma para [texto] (más es más probable). */
	fun puntuar(texto: String): Map<String, Double> {
		val trozo = if (texto.length > MAXIMO_CARACTERES) texto.substring(0, MAXIMO_CARACTERES) else texto
		// El apóstrofo tipográfico (c’est, don’t) como el recto
		val minusculas = trozo.lowercase(Locale.ROOT).replace('’', '\'')
		val puntos = HashMap<String, Double>()
		for (m in PALABRA.findAll(minusculas)) {
			val palabra = m.value
			val veces = repartos[palabra] ?: continue
			for ((idioma, lista) in PALABRAS) if (palabra in lista) puntos.merge(idioma, 1.0 / veces, Double::plus)
		}
		val letras = HashMap<String, Double>()
		for (c in minusculas) {
			val lista = LETRAS[c] ?: continue
			for ((idioma, peso) in lista) letras.merge(idioma, peso, Double::plus)
		}
		for ((idioma, valor) in letras) puntos.merge(idioma, minOf(MAXIMO_LETRAS, valor), Double::plus)
		return puntos
	}

	/**
	 * El idioma de [texto] si está claro (uno saca al menos 2 puntos y bastante
	 * más que el segundo); si no, null.
	 */
	fun detectar(texto: String): String? {
		val orden = puntuar(texto).entries.sortedByDescending { it.value }
		val primero = orden.firstOrNull() ?: return null
		val segundo = orden.getOrNull(1)?.value ?: 0.0
		return if (primero.value >= 2.0 && primero.value >= segundo * 1.5 && primero.value - segundo >= 1.0) primero.key else null
	}

	/**
	 * El más probable de [candidatos] aunque no esté claro, o null si ninguno
	 * tiene ni una pista o empatan. Para cuando [detectar] no decide y solo hay
	 * unos pocos traductores donde elegir.
	 */
	fun masProbable(texto: String, candidatos: Collection<String>): String? {
		val puntos = puntuar(texto)
		val orden = candidatos.map { it to (puntos[it] ?: 0.0) }.sortedByDescending { it.second }
		val primero = orden.firstOrNull() ?: return null
		if (primero.second <= 0.0) return null
		if ((orden.getOrNull(1)?.second ?: 0.0) >= primero.second) return null
		return primero.first
	}
}
