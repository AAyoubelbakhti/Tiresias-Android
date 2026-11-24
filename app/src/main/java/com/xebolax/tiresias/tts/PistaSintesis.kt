package com.xebolax.tiresias.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import com.xebolax.tiresias.voz.PistaAudio

/**
 * Una «pista» que no suena: entrega las muestras de una voz incluida (Eloquence, una voz neuronal) a quien la pidió
 * por el motor de voz del sistema. Así esas voces sirven a otras apps y a otros lectores igual que eSpeak y Pico.
 *
 * La frecuencia se conoce con las primeras muestras: entonces se abre la respuesta con ella.
 */
class PistaSintesis(private val respuesta: SynthesisCallback, private val parar: () -> Boolean) :
	PistaAudio(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) {

	/** La frecuencia con la que se abrió la respuesta (0: aún no). */
	var frecuencia = 0
		private set

	/** Si la respuesta se abrió y aceptó muestras. */
	var empezada = false
		private set

	override fun escribir(muestras: ShortArray, hz: Int, sigue: () -> Boolean): Boolean {
		if (muestras.isEmpty()) return true
		if (frecuencia == 0) {
			if (respuesta.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return false
			frecuencia = hz
			empezada = true
		}
		// (una voz que cambia de frecuencia a mitad no se puede entregar: se deja aquí)
		if (hz != frecuencia) return false
		val bytes = ByteArray(muestras.size * 2)
		for (i in muestras.indices) {
			val m = muestras[i].toInt()
			bytes[2 * i] = (m and 0xFF).toByte()
			bytes[2 * i + 1] = (m shr 8 and 0xFF).toByte()
		}
		val maximo = respuesta.maxBufferSize.coerceAtLeast(1024)
		var desde = 0
		while (desde < bytes.size) {
			if (parar() || !sigue()) return true
			val n = minOf(maximo, bytes.size - desde)
			if (respuesta.audioAvailable(bytes, desde, n) != TextToSpeech.SUCCESS) return false
			desde += n
		}
		return true
	}

	/** Lo entregado ya es del sistema: no hay nada que esperar. */
	override fun esperarFin(sigue: () -> Boolean): Boolean = !parar()

	override fun cortar() {}
}
