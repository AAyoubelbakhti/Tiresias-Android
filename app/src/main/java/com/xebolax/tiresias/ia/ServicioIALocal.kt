package com.xebolax.tiresias.ia

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.xebolax.tiresias.base.Registro
import java.io.File

/**
 * El modelo descargado, en su propio proceso (`android:process=":ia"`).
 *
 * Un modelo de varios gigas no puede compartir proceso con el lector: si
 * Android se queda sin memoria, mata este proceso y no el de Tiresias, que
 * dice que no había memoria suficiente y sigue funcionando. Atiende las
 * preguntas de una en una en su hilo y mantiene el modelo cargado mientras haya
 * alguien conectado.
 */
class ServicioIALocal : Service() {
	// Prioridad normal, no de fondo: en Android, un hilo de fondo (y los que crea el modelo, que la heredan) va a los
	// núcleos lentos, y en algunos Samsung solo a uno o dos. Con la de fondo, Gemma tardaba tanto que no acababa nunca
	private val hilo = HandlerThread("tiresias-ia", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
	private var motor: com.google.ai.edge.litertlm.Engine? = null
	private var rutaCargada = ""
	private var gpuCargada = false

	private val manejador = Handler(hilo.looper) { m ->
		if (m.what == PREGUNTAR) responder(m.data, m.replyTo)
		true
	}
	private val mensajero = Messenger(manejador)

	override fun onBind(intent: Intent?): IBinder = mensajero.binder

	override fun onDestroy() {
		super.onDestroy()
		cerrar()
		hilo.quitSafely()
	}

	private fun cerrar() {
		runCatching { motor?.close() }
		motor = null
		rutaCargada = ""
	}

	private fun cargar(ruta: String, gpu: Boolean): com.google.ai.edge.litertlm.Engine {
		motor?.let { if (ruta == rutaCargada && gpu == gpuCargada) return it else cerrar() }
		comprobarMemoria(File(ruta).length())
		val cache = File(cacheDir, "litertlm").apply { mkdirs() }.absolutePath
		val especulativa = especulativa(ruta)
		fun crear(conGpu: Boolean): com.google.ai.edge.litertlm.Engine {
			val backend = if (conGpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU()
			val config = com.google.ai.edge.litertlm.EngineConfig(
				modelPath = ruta,
				backend = backend,
				visionBackend = backend,
				// 4096 de contexto sobran para describir una imagen o una conversación corta, y la memoria que
				// reserva el modelo crece con él: con 8192, un móvil de 6 u 8 GB acababa tirando de la memoria de
				// intercambio y la respuesta tardaba más de un minuto (Backtalk lo midió en un Pixel 9a)
				maxNumTokens = 4096,
				maxNumImages = 1,
				cacheDir = cache,
			)
			return crearMotor(config, especulativa)
		}
		// La tarjeta gráfica es más rápida pero su controlador falla en algunos móviles: si falla, procesador
		val desde = android.os.SystemClock.uptimeMillis()
		val m = if (!gpu) crear(false) else try {
			crear(true)
		} catch (e: Throwable) {
			if (e is OutOfMemoryError) throw e
			Registro.w(ETIQUETA, "La IA no pudo usar la tarjeta gráfica; se usa el procesador", e)
			crear(false)
		}
		motor = m
		rutaCargada = ruta
		gpuCargada = gpu
		Registro.i(ETIQUETA, "Modelo cargado en ${android.os.SystemClock.uptimeMillis() - desde} ms (${if (gpu) "tarjeta gráfica" else "procesador"}, ${Runtime.getRuntime().availableProcessors()} núcleos)")
		return m
	}

	/**
	 * Gemma 4 trae una cabeza que predice varias palabras a la vez (decodificación especulativa): con ella, en un
	 * Galaxy S25, Backtalk midió un 30 % más rápido. El motor falla si se pide con un modelo que no la tiene, así
	 * que se pregunta antes al archivo; null si no se sabe (el motor hace lo que diga el modelo).
	 */
	private fun especulativa(ruta: String): Boolean? = runCatching {
		com.google.ai.edge.litertlm.ModelInfo.from(ruta).use { (it as? com.google.ai.edge.litertlm.ModelInfo.Llm)?.hasSpeculativeDecodingSupport() }
	}.onFailure { Registro.i(ETIQUETA, "No se pudo saber qué admite el modelo: ${it.javaClass.simpleName}") }.getOrNull()

	@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
	private fun crearMotor(config: com.google.ai.edge.litertlm.EngineConfig, especulativa: Boolean?): com.google.ai.edge.litertlm.Engine {
		com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding = especulativa
		return com.google.ai.edge.litertlm.Engine(config).also { it.initialize() }
	}

	/**
	 * Antes de cargar: si Android dice que queda poca memoria libre, el modelo no se carga. Cargado así, Android
	 * empezaba a cerrar apps y a tirar de la memoria de intercambio, y la respuesta no llegaba nunca («se queda
	 * pensando»). Se pide al menos la mitad del tamaño del modelo libre (el archivo se lee a trozos desde el disco).
	 */
	private fun comprobarMemoria(bytesModelo: Long) {
		val info = android.app.ActivityManager.MemoryInfo()
		getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info) ?: return
		Registro.i(ETIQUETA, "Memoria libre: ${info.availMem / MB} MB de ${info.totalMem / MB} MB; el modelo ocupa ${bytesModelo / MB} MB")
		if (info.lowMemory || info.availMem < bytesModelo / 2) throw PocaMemoria(info.availMem, bytesModelo)
	}

	private class PocaMemoria(val libre: Long, val necesita: Long) : Exception("Poca memoria libre")

	private fun responder(datos: Bundle, destino: Messenger?) {
		val r = Bundle()
		val desde = android.os.SystemClock.uptimeMillis()
		try {
			val m = cargar(datos.getString(MODELO).orEmpty(), datos.getBoolean(GPU))
			val textos = datos.getStringArray(TEXTOS).orEmpty()
			val deUsuario = datos.getBooleanArray(DE_USUARIO) ?: BooleanArray(textos.size) { true }
			val usuario = datos.getString(TURNO_USUARIO).orEmpty()
			val asistente = datos.getString(TURNO_ASISTENTE).orEmpty()
			val texto = buildString {
				appendLine(datos.getString(SISTEMA).orEmpty())
				appendLine()
				textos.forEachIndexed { i, t ->
					append(if (deUsuario.getOrElse(i) { true }) usuario else asistente).append(": ").appendLine(t)
				}
			}
			val partes = mutableListOf<com.google.ai.edge.litertlm.Content>()
			datos.getString(IMAGEN)?.let { partes += com.google.ai.edge.litertlm.Content.ImageFile(it) }
			partes += com.google.ai.edge.litertlm.Content.Text(texto)
			val respuesta = m.createConversation().use { conversacion ->
				val res = conversacion.sendMessage(com.google.ai.edge.litertlm.Contents.of(partes))
				res.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
			}
			r.putString(RESPUESTA, respuesta)
			// Solo el tiempo y el largo, nunca el texto
			Registro.i(ETIQUETA, "Respuesta en ${android.os.SystemClock.uptimeMillis() - desde} ms (${respuesta.length} caracteres, ${if (datos.getString(IMAGEN) != null) "con" else "sin"} imagen)")
		} catch (e: PocaMemoria) {
			r.putString(ERROR, POCA_MEMORIA)
			r.putLong(LIBRE, e.libre)
			r.putLong(NECESITA, e.necesita)
		} catch (e: OutOfMemoryError) {
			cerrar()
			r.putString(ERROR, SIN_MEMORIA)
		} catch (e: Throwable) {
			Registro.e(ETIQUETA, "La IA descargada falló", e)
			cerrar()
			r.putString(ERROR, e.message ?: e.javaClass.simpleName)
		}
		runCatching { destino?.send(Message.obtain().apply { data = r }) }
	}

	companion object {
		private const val ETIQUETA = "ServicioIALocal"
		const val PREGUNTAR = 1
		const val SISTEMA = "sistema"
		const val TEXTOS = "textos"
		const val DE_USUARIO = "de_usuario"
		const val IMAGEN = "imagen"
		const val MODELO = "modelo"
		const val GPU = "gpu"
		const val TURNO_USUARIO = "turno_usuario"
		const val TURNO_ASISTENTE = "turno_asistente"
		const val RESPUESTA = "respuesta"
		const val ERROR = "error"
		const val SIN_MEMORIA = "sin_memoria"
		const val POCA_MEMORIA = "poca_memoria"
		const val LIBRE = "libre"
		const val NECESITA = "necesita"
		private const val MB = 1_048_576L
	}
}
