package com.xebolax.tiresias.traduccion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Un traductor OPUS-MT (Marian, de Helsinki-NLP) en ONNX Runtime, para un par
 * de idiomas (de inglés a español, por ejemplo). Sin Internet.
 *
 * - **Codificador**: lee la frase entera de una vez.
 * - **Decodificador fusionado** con caché: escribe la traducción pieza a
 *   pieza (búsqueda voraz: siempre la más probable). En el primer paso va sin
 *   caché (pasados vacíos y `use_cache_branch` falso); después se le dan los
 *   pasados que devolvió: los del decodificador cambian en cada paso y los del
 *   codificador se quedan los del primero.
 * - Los textos largos se traducen por líneas y frases: Marian traduce mejor
 *   frase a frase y no admite más de 512 piezas. Una frase sin puntos muy larga
 *   se parte por la mitad de sus palabras.
 *
 * Los modelos (unos 110 MB en el disco) se cargan al traducir y se cierran
 * solos tras [INACTIVIDAD_MS] sin uso, para devolver la memoria. Bloquea:
 * llámese fuera del hilo principal. No depende de Android (se prueba en el PC)
 * ni registra nada: los fallos salen como excepción y nunca con el texto.
 */
class TraductorOpus(private val carpeta: File, private val entorno: OrtEnvironment, private val hilos: Int = 4) : AutoCloseable {
	private var codificador: OrtSession? = null
	private var decodificador: OrtSession? = null
	private var tokenizador: TokenizadorUnigram? = null
	private var inicio = 0
	private var relleno = -1
	private var fin = 0
	private var cabezas = 8
	private var dimension = 64
	private var largoMaximo = 512
	private var cierre: ScheduledFuture<*>? = null

	val abierto: Boolean @Synchronized get() = codificador != null

	@Synchronized
	private fun abrir() {
		if (codificador != null) return
		val tok = TokenizadorUnigram.desdeArchivo(File(carpeta, TOKENIZADOR))
		val config = JSONObject(File(carpeta, CONFIGURACION).readText(Charsets.UTF_8))
		inicio = config.optInt("decoder_start_token_id", config.optInt("pad_token_id", tok.tamanoVocabulario - 1))
		relleno = config.optInt("pad_token_id", inicio)
		fin = config.optInt("eos_token_id", tok.idFin)
		cabezas = config.optInt("decoder_attention_heads", 8)
		dimension = config.optInt("d_model", 512) / cabezas
		largoMaximo = config.optInt("max_position_embeddings", 512)
		val opciones = OrtSession.SessionOptions().apply {
			setIntraOpNumThreads(hilos)
			setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
		}
		val cod = entorno.createSession(File(carpeta, CODIFICADOR).path, opciones)
		try {
			decodificador = entorno.createSession(File(carpeta, DECODIFICADOR).path, opciones)
		} catch (e: Exception) {
			cod.close()
			throw e
		}
		codificador = cod
		tokenizador = tok
	}

	/** Libera los modelos y el vocabulario. Se vuelven a cargar al traducir otra vez. */
	@Synchronized
	override fun close() {
		cierre?.cancel(false)
		cierre = null
		val cod = codificador
		val dec = decodificador
		codificador = null
		decodificador = null
		tokenizador = null
		try {
			cod?.close()
		} finally {
			dec?.close()
		}
	}

	/**
	 * La traducción de [texto], respetando sus saltos de línea. [sigue] se
	 * consulta entre frase y frase: si devuelve false, se para con
	 * [InterruptedException].
	 */
	@Synchronized
	fun traducir(texto: String, sigue: () -> Boolean = { true }): String {
		cierre?.cancel(false)
		cierre = null
		try {
			abrir()
			val lineas = texto.split('\n')
			val salida = StringBuilder()
			for ((n, linea) in lineas.withIndex()) {
				if (n > 0) salida.append('\n')
				var primera = true
				for (frase in frases(linea)) {
					if (!sigue()) throw InterruptedException()
					if (!primera) salida.append(' ')
					salida.append(traducirFrase(frase))
					primera = false
				}
			}
			return salida.toString()
		} finally {
			cierre = PROGRAMADOR.schedule({ close() }, INACTIVIDAD_MS, TimeUnit.MILLISECONDS)
		}
	}

	/** Una frase; si tiene demasiadas piezas, por mitades. Lo que no tiene letras (números, signos) se deja igual. */
	private fun traducirFrase(frase: String): String {
		if (frase.none { it.isLetter() }) return frase
		val tok = tokenizador ?: throw IllegalStateException("Traductor cerrado")
		val ids = tok.codificar(frase)
		if (ids.size > MAXIMO_PIEZAS) {
			val palabras = frase.split(' ').filter { it.isNotEmpty() }
			if (palabras.size > 1) {
				val mitad = palabras.size / 2
				return traducirFrase(palabras.subList(0, mitad).joinToString(" ")) + " " + traducirFrase(palabras.subList(mitad, palabras.size).joinToString(" "))
			}
		}
		val recortados = if (ids.size > MAXIMO_PIEZAS) ids.copyOf(MAXIMO_PIEZAS).also { it[MAXIMO_PIEZAS - 1] = tok.idFin } else ids
		return tok.decodificar(generar(recortados))
	}

	/** Las piezas de la traducción de [ids] (con el fin de frase), por búsqueda voraz con caché. */
	private fun generar(ids: IntArray): IntArray {
		val cod = codificador ?: throw IllegalStateException("Traductor cerrado")
		val dec = decodificador ?: throw IllegalStateException("Traductor cerrado")
		val n = ids.size.toLong()
		val cerrar = ArrayList<AutoCloseable>()
		try {
			val entrada = OnnxTensor.createTensor(entorno, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, n)).also { cerrar += it }
			val mascara = OnnxTensor.createTensor(entorno, LongBuffer.wrap(LongArray(ids.size) { 1L }), longArrayOf(1, n)).also { cerrar += it }
			val rCod = cod.run(mapOf("input_ids" to entrada, "attention_mask" to mascara)).also { cerrar += it }
			val ocultos = rCod.get(0) as OnnxTensor

			val nombres = dec.inputNames
			val vacio = OnnxTensor.createTensor(entorno, FloatBuffer.allocate(0), longArrayOf(1, cabezas.toLong(), 0, dimension.toLong())).also { cerrar += it }
			val pasados = HashMap<String, OnnxTensor>()
			for (nombre in nombres) if (nombre.startsWith(PASADO)) pasados[nombre] = vacio
			val conRama = RAMA in nombres
			val sinCache = OnnxTensor.createTensor(entorno, booleanArrayOf(false)).also { cerrar += it }
			val conCache = OnnxTensor.createTensor(entorno, booleanArrayOf(true)).also { cerrar += it }

			val salida = ArrayList<Int>()
			var ultimo = inicio
			var primero: OrtSession.Result? = null
			var anterior: OrtSession.Result? = null
			try {
				val pasos = minOf(3 * ids.size + 10, largoMaximo)
				for (paso in 0 until pasos) {
					val entradas = HashMap<String, OnnxTensor>(pasados)
					val pieza = OnnxTensor.createTensor(entorno, LongBuffer.wrap(longArrayOf(ultimo.toLong())), longArrayOf(1, 1))
					entradas["input_ids"] = pieza
					entradas["encoder_hidden_states"] = ocultos
					entradas["encoder_attention_mask"] = mascara
					if (conRama) entradas[RAMA] = if (paso == 0) sinCache else conCache
					val r = try {
						dec.run(entradas)
					} finally {
						pieza.close()
					}
					val siguiente = masProbable(r.get(0) as OnnxTensor)
					// Los pasados para el paso siguiente: los del codificador solo del primero
					for ((nombre, valor) in r) {
						if (!nombre.startsWith(PRESENTE)) continue
						val clave = PASADO + nombre.substring(PRESENTE.length)
						if (clave in pasados && (paso == 0 || ".decoder." in nombre)) pasados[clave] = valor as OnnxTensor
					}
					// El resultado anterior ya no hace falta (el primero sí: guarda los del codificador)
					if (anterior != null && anterior !== primero) anterior.close()
					anterior = r
					if (paso == 0) primero = r
					if (siguiente == fin) break
					salida += siguiente
					ultimo = siguiente
				}
			} finally {
				if (anterior != null && anterior !== primero) anterior.close()
				primero?.close()
			}
			return salida.toIntArray()
		} finally {
			for (c in cerrar.asReversed()) c.close()
		}
	}

	/** La pieza más probable de la última posición de [logits], sin el relleno (nunca se genera). */
	private fun masProbable(logits: OnnxTensor): Int {
		val forma = logits.info.shape
		val vocabulario = forma.last().toInt()
		val b = logits.floatBuffer
		val base = b.limit() - vocabulario
		var mejor = -1
		var valor = Float.NEGATIVE_INFINITY
		for (k in 0 until vocabulario) {
			if (k == relleno) continue
			val v = b.get(base + k)
			if (v > valor) {
				valor = v
				mejor = k
			}
		}
		return if (mejor < 0) fin else mejor
	}

	companion object {
		const val CODIFICADOR = "codificador.onnx"
		const val DECODIFICADOR = "decodificador.onnx"
		const val TOKENIZADOR = "tokenizer.json"
		const val CONFIGURACION = "config.json"

		/** Tras este rato sin traducir, se cierran los modelos (unos cientos de MB de memoria). */
		const val INACTIVIDAD_MS = 3 * 60_000L
		/** Las piezas de una frase como mucho (Marian admite 512; con menos traduce mejor y más rápido). */
		private const val MAXIMO_PIEZAS = 256

		private const val PASADO = "past_key_values"
		private const val PRESENTE = "present"
		private const val RAMA = "use_cache_branch"

		private val PROGRAMADOR = Executors.newSingleThreadScheduledExecutor { r ->
			Thread(r, "tiresias-traductor").apply { isDaemon = true }
		}

		private val FINAL_DE_FRASE = setOf('.', '!', '?', '…', ';', '。', '！', '？')
		private val CIERRES = setOf('"', '\'', '»', '”', '’', ')', ']')

		/**
		 * Las frases de [linea]: se corta tras «.», «!», «?», «…» o «;» (y las
		 * comillas o paréntesis que los cierren) cuando sigue un espacio. Sin
		 * espacios a los lados; las vacías no salen.
		 */
		fun frases(linea: String): List<String> {
			val lista = ArrayList<String>()
			var inicio = 0
			var i = 0
			while (i < linea.length) {
				if (linea[i] in FINAL_DE_FRASE) {
					var j = i + 1
					while (j < linea.length && (linea[j] in FINAL_DE_FRASE || linea[j] in CIERRES)) j++
					if (j >= linea.length || linea[j].isWhitespace()) {
						linea.substring(inicio, j).trim().takeIf { it.isNotEmpty() }?.let { lista += it }
						inicio = j
					}
					i = j
				} else i++
			}
			linea.substring(inicio).trim().takeIf { it.isNotEmpty() }?.let { lista += it }
			return lista
		}
	}
}
