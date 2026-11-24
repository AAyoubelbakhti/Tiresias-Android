package com.xebolax.tiresias.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.xebolax.tiresias.base.Ajustes
import com.xebolax.tiresias.base.Idioma
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.recursos.Recursos
import com.xebolax.tiresias.recursos.VocesNeuronales
import com.xebolax.tiresias.voz.BibliotecaEloquence
import com.xebolax.tiresias.voz.EspeakNativo
import com.xebolax.tiresias.voz.Marcado
import com.xebolax.tiresias.voz.MotorEloquence
import com.xebolax.tiresias.voz.MotorPiper
import com.xebolax.tiresias.voz.PicoNativo
import com.xebolax.tiresias.voz.Resultado
import com.xebolax.tiresias.voz.VozPico
import java.util.Locale

/**
 * «Voces de Tiresias» como motor de voz del sistema: eSpeak NG, Pico, Eloquence (si el APK la trae para este móvil) y
 * las voces neuronales descargadas, sin Internet.
 *
 * Sin Google Play Services muchos móviles no tienen una voz en español: así cualquier app (y otros lectores) la
 * tienen. Qué voz se usa en cada idioma cuando la app no elige una lo dice Ajustes, Voz, «Voz para otras apps»: de
 * fábrica, la misma que usa Tiresias (Cronopio pidió poder leer con Eloquence desde otro lector). Las apps que dejan
 * elegir voz ven todas.
 *
 * Android llama a la síntesis siempre desde el mismo hilo; [onStop] llega desde otro y solo levanta una marca.
 */
class ServicioTtsTiresias : TextToSpeechService() {
	private var pico: PicoNativo? = null
	private var eloquence: MotorEloquence? = null
	private var piper: MotorPiper? = null
	@Volatile private var parar = false
	@Volatile private var cargada: VozTiresias? = null
	private val ajustes by lazy { Ajustes(this) }

	/** Una voz de este motor: [motor] es «espeak», «pico», «eloquence» o el identificador de una voz neuronal. */
	data class VozTiresias(val nombre: String, val locale: Locale, val motor: String)

	override fun onDestroy() {
		super.onDestroy()
		pico?.cerrar()
		pico = null
		eloquence?.cerrar()
		eloquence = null
		piper?.cerrar()
		piper = null
	}

	/** Todas las voces que hay en este móvil ahora (las neuronales, si están descargadas). */
	private fun voces(): List<VozTiresias> = buildList {
		addAll(BASICAS)
		if (BibliotecaEloquence.disponible(this@ServicioTtsTiresias)) addAll(ELOQUENCE)
		for (v in VocesNeuronales.CATALOGO) {
			if (Recursos.instalado(this@ServicioTtsTiresias, v.recurso)) add(VozTiresias("${v.locale.toLanguageTag()}-piper-${v.id}", v.locale, MotorPiper.PREFIJO + v.id))
		}
	}

	override fun onIsLanguageAvailable(idioma: String?, pais: String?, variante: String?): Int = disponibilidad(voces(), idioma, pais)

	override fun onGetLanguage(): Array<String> {
		val l = (cargada ?: BASICAS.first()).locale
		return arrayOf(l.isO3Language, runCatching { l.isO3Country }.getOrDefault(""), "")
	}

	override fun onLoadLanguage(idioma: String?, pais: String?, variante: String?): Int {
		val todas = voces()
		val r = disponibilidad(todas, idioma, pais)
		if (r >= TextToSpeech.LANG_AVAILABLE) cargada = elegir(todas, idioma, pais)
		return r
	}

	override fun onStop() {
		parar = true
		EspeakNativo.cancelar()
		eloquence?.cortar()
		piper?.cortar()
	}

	override fun onGetVoices(): List<Voice> = voces().map {
		Voice(it.nombre, it.locale, if (it.motor.startsWith(MotorPiper.PREFIJO)) Voice.QUALITY_HIGH else Voice.QUALITY_NORMAL,
			if (it.motor.startsWith(MotorPiper.PREFIJO)) Voice.LATENCY_NORMAL else Voice.LATENCY_VERY_LOW, false, emptySet())
	}

	override fun onIsValidVoiceName(nombre: String?): Int = if (voces().any { it.nombre == nombre }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

	override fun onLoadVoice(nombre: String?): Int {
		cargada = voces().firstOrNull { it.nombre == nombre } ?: return TextToSpeech.ERROR
		return TextToSpeech.SUCCESS
	}

	override fun onGetDefaultVoiceNameFor(idioma: String?, pais: String?, variante: String?): String? {
		val todas = voces()
		return if (disponibilidad(todas, idioma, pais) >= TextToSpeech.LANG_AVAILABLE) elegir(todas, idioma, pais).nombre else null
	}

	override fun onSynthesizeText(peticion: SynthesisRequest, respuesta: SynthesisCallback) {
		parar = false
		val todas = voces()
		val voz = peticion.voiceName?.let { n -> todas.firstOrNull { it.nombre == n } }
			?: if (disponibilidad(todas, peticion.language, peticion.country) >= TextToSpeech.LANG_AVAILABLE) elegir(todas, peticion.language, peticion.country)
			// Un idioma que no hay: mejor en el idioma de Tiresias que callar
			else elegir(todas, Idioma.efectivo(this).language, Idioma.efectivo(this).country)
		val texto = peticion.charSequenceText?.toString() ?: @Suppress("DEPRECATION") peticion.text.orEmpty()
		try {
			val pista = PistaSintesis(respuesta) { parar }
			val hecho = when {
				voz.motor == "pico" -> { pico(voz, texto, peticion, respuesta); true }
				voz.motor == "eloquence" -> propia(voz, texto, peticion, respuesta, pista, eloquence(pista))
				voz.motor.startsWith(MotorPiper.PREFIJO) -> propia(voz, texto, peticion, respuesta, pista, piper(voz.motor, pista))
				else -> { espeak(voz, texto, peticion, respuesta); true }
			}
			// Si Eloquence o la voz neuronal fallan antes de empezar, eSpeak en su lugar: mejor eso que callar
			if (!hecho) espeak(elegirEspeak(voz.locale.language, voz.locale.country), texto, peticion, respuesta)
		} catch (e: Exception) {
			Registro.e(ETIQUETA, "Fallo al sintetizar como motor del sistema", e)
			respuesta.error(TextToSpeech.ERROR_SYNTHESIS)
		}
	}

	/** El motor de Eloquence con la pista que entrega a esta petición (se rehace por petición: la pista es de ella). */
	private fun eloquence(pista: PistaSintesis): MotorEloquence? {
		eloquence?.cerrar()
		eloquence = null
		if (!BibliotecaEloquence.disponible(this)) return null
		return MotorEloquence(this, pista).also { eloquence = it }
	}

	private fun piper(id: String, pista: PistaSintesis): MotorPiper? {
		piper?.cerrar()
		piper = null
		return MotorPiper.para(this, id, pista)?.also { piper = it }
	}

	/**
	 * Eloquence o una voz neuronal: hablan por su [PistaSintesis]. Devuelve false si fallaron sin dar nada (para
	 * probar con eSpeak); si ya habían dado algo, se cierra la respuesta con lo que hubo.
	 */
	private fun propia(
		voz: VozTiresias, texto: String, peticion: SynthesisRequest, r: SynthesisCallback, pista: PistaSintesis, motor: com.xebolax.tiresias.voz.Motor?,
	): Boolean {
		if (motor == null) return false
		// La velocidad y el tono de Android van de 100 en 100, como los de Tiresias
		val resultado = motor.hablar(texto, voz.locale, peticion.speechRate.coerceIn(30, 600), peticion.pitch.coerceIn(50, 200), { !parar }, {})
		if (resultado == Resultado.FALLO && !pista.empezada) {
			Registro.w(ETIQUETA, "La voz ${voz.motor.substringBefore(':')} falló como motor del sistema")
			return false
		}
		// Sin nada que decir (solo espacios) la respuesta se abre igual, para que la app sepa que acabó
		if (!pista.empezada) r.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
		r.done()
		return true
	}

	private fun espeak(voz: VozTiresias, texto: String, peticion: SynthesisRequest, r: SynthesisCallback) {
		if (!EspeakNativo.iniciar(this)) {
			r.error(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		if (r.start(EspeakNativo.frecuenciaMuestras, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
		for (frase in Marcado.frases(Marcado.limpiar(texto))) {
			if (parar) break
			val m = EspeakNativo.sintetizar(this, frase, voz.locale, peticion.speechRate, peticion.pitch)
			if (m == null) {
				r.error(TextToSpeech.ERROR_SYNTHESIS)
				return
			}
			if (!entregar(m, r)) break
		}
		r.done()
	}

	private fun pico(voz: VozTiresias, texto: String, peticion: SynthesisRequest, r: SynthesisCallback) {
		val vozPico = VozPico.para(voz.locale)
		val motor = pico?.takeIf { it.voz == vozPico } ?: run {
			pico?.cerrar()
			PicoNativo.abrir(this, vozPico).also { pico = it }
		}
		if (motor == null) {
			r.error(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		if (r.start(PicoNativo.FRECUENCIA, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
		for (frase in Marcado.frases(Marcado.limpiar(texto))) {
			if (parar) break
			val m = motor.sintetizar(Marcado.preparar(frase, peticion.speechRate, peticion.pitch))
			if (m == null) {
				motor.reiniciar()
				r.error(TextToSpeech.ERROR_SYNTHESIS)
				return
			}
			if (!entregar(m, r)) break
		}
		if (parar) motor.reiniciar()
		r.done()
	}

	/** Entrega las muestras al sistema en trozos del tamaño que admite. false si hay que parar. */
	private fun entregar(muestras: ShortArray, r: SynthesisCallback): Boolean {
		val bytes = ByteArray(muestras.size * 2)
		for (i in muestras.indices) {
			val m = muestras[i].toInt()
			bytes[2 * i] = (m and 0xFF).toByte()
			bytes[2 * i + 1] = (m shr 8 and 0xFF).toByte()
		}
		val maximo = r.maxBufferSize.coerceAtLeast(1024)
		var desde = 0
		while (desde < bytes.size) {
			if (parar) return false
			val n = minOf(maximo, bytes.size - desde)
			if (r.audioAvailable(bytes, desde, n) != TextToSpeech.SUCCESS) return false
			desde += n
		}
		return true
	}

	/**
	 * La voz de [idioma] cuando la app no elige: la del motor puesto en «Voz para otras apps» si tiene ese idioma; si
	 * no, eSpeak.
	 */
	private fun elegir(todas: List<VozTiresias>, idioma: String?, p: String?): VozTiresias {
		val i = corto(idioma)
		val c = pais(p)
		val preferido = when (val m = ajustes.motorOtrasApps) {
			Ajustes.MOTOR_OTRAS_IGUAL -> when {
				ajustes.motor == Ajustes.MOTOR_ELOQUENCE -> "eloquence"
				ajustes.motor == Ajustes.MOTOR_PICO -> "pico"
				ajustes.motor.startsWith(MotorPiper.PREFIJO) -> ajustes.motor
				else -> "espeak"
			}
			else -> m
		}
		val delMotor = todas.filter { it.locale.language == i && (it.motor == preferido || preferido == "piper" && it.motor.startsWith(MotorPiper.PREFIJO)) }
		if (delMotor.isNotEmpty()) {
			if (i == "es" && c.isNotEmpty() && c != "ES") delMotor.firstOrNull { it.locale.country == c }?.let { return it }
			return delMotor.firstOrNull { it.locale.country == c } ?: delMotor.first()
		}
		return elegirEspeak(i, c)
	}

	companion object {
		private const val ETIQUETA = "ServicioTts"

		/** eSpeak NG y Pico, que van siempre dentro. */
		val BASICAS = listOf(
			VozTiresias("es-ES-espeak", Locale("es", "ES"), "espeak"),
			VozTiresias("es-419-espeak", Locale("es", "419"), "espeak"),
			VozTiresias("es-MX-espeak", Locale("es", "MX"), "espeak"),
			VozTiresias("en-US-espeak", Locale.US, "espeak"),
			VozTiresias("en-GB-espeak", Locale.UK, "espeak"),
			VozTiresias("fr-FR-espeak", Locale.FRANCE, "espeak"),
			VozTiresias("de-DE-espeak", Locale.GERMANY, "espeak"),
			VozTiresias("it-IT-espeak", Locale.ITALY, "espeak"),
			VozTiresias("pt-PT-espeak", Locale("pt", "PT"), "espeak"),
			VozTiresias("pt-BR-espeak", Locale("pt", "BR"), "espeak"),
			VozTiresias("es-ES-pico", Locale("es", "ES"), "pico"),
			VozTiresias("en-US-pico", Locale.US, "pico"),
			VozTiresias("en-GB-pico", Locale.UK, "pico"),
		)

		/** Los idiomas de Eloquence que tienen sentido para Tiresias (los de evvdroid de alfabeto latino). */
		val ELOQUENCE = listOf(
			VozTiresias("es-ES-eloquence", Locale("es", "ES"), "eloquence"),
			VozTiresias("es-MX-eloquence", Locale("es", "MX"), "eloquence"),
			VozTiresias("en-US-eloquence", Locale.US, "eloquence"),
			VozTiresias("en-GB-eloquence", Locale.UK, "eloquence"),
			VozTiresias("fr-FR-eloquence", Locale.FRANCE, "eloquence"),
			VozTiresias("fr-CA-eloquence", Locale.CANADA_FRENCH, "eloquence"),
			VozTiresias("de-DE-eloquence", Locale.GERMANY, "eloquence"),
			VozTiresias("it-IT-eloquence", Locale.ITALY, "eloquence"),
			VozTiresias("pt-BR-eloquence", Locale("pt", "BR"), "eloquence"),
			VozTiresias("fi-FI-eloquence", Locale("fi", "FI"), "eloquence"),
		)

		/** (las de antes, para quien las use por nombre) */
		val VOCES: List<VozTiresias> get() = BASICAS

		private fun corto(i: String?): String = when (i?.lowercase(Locale.ROOT)) {
			"spa" -> "es"
			"eng" -> "en"
			"fra", "fre" -> "fr"
			"deu", "ger" -> "de"
			"ita" -> "it"
			"por" -> "pt"
			"fin" -> "fi"
			else -> i?.lowercase(Locale.ROOT).orEmpty()
		}

		private fun pais(p: String?): String = when (p?.uppercase(Locale.ROOT)) {
			"ESP" -> "ES"; "USA" -> "US"; "GBR" -> "GB"; "MEX" -> "MX"; "FRA" -> "FR"; "DEU" -> "DE"; "ITA" -> "IT"; "PRT" -> "PT"; "BRA" -> "BR"
			"CAN" -> "CA"; "FIN" -> "FI"
			else -> p?.uppercase(Locale.ROOT).orEmpty()
		}

		fun disponibilidad(idioma: String?, p: String?): Int = disponibilidad(BASICAS, idioma, p)

		fun disponibilidad(voces: List<VozTiresias>, idioma: String?, p: String?): Int {
			val i = corto(idioma)
			val c = pais(p)
			val delIdioma = voces.filter { it.locale.language == i }
			if (delIdioma.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
			return if (delIdioma.any { it.locale.country == c }) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
		}

		fun elegir(idioma: String?, p: String?): VozTiresias = elegirEspeak(corto(idioma), pais(p))

		fun elegirEspeak(idioma: String?, p: String?): VozTiresias {
			val i = corto(idioma)
			val c = pais(p)
			val delIdioma = BASICAS.filter { it.locale.language == i && it.motor == "espeak" }
			// Español de América (cualquier país que no sea España): la variante latinoamericana
			if (i == "es" && c.isNotEmpty() && c != "ES") return delIdioma.firstOrNull { it.locale.country == c } ?: BASICAS[1]
			return delIdioma.firstOrNull { it.locale.country == c } ?: delIdioma.firstOrNull() ?: BASICAS.first()
		}
	}
}
