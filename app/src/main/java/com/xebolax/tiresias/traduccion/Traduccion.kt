package com.xebolax.tiresias.traduccion

import android.content.Context
import com.xebolax.tiresias.base.Onnx
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.recursos.ModelosTraduccion
import com.xebolax.tiresias.recursos.Recursos
import java.util.Locale

/**
 * La traducción sin conexión: elige el traductor descargado que sirve para un
 * texto y lo traduce sin Internet ([TraductorOpus]).
 *
 * 1. Se mira en qué idioma está el texto ([DetectorIdioma]).
 * 2. Si ya está en el idioma de destino, no se traduce ([Resultado.MismoIdioma]).
 * 3. Si hay traductor de ese idioma al de destino, se traduce ([Resultado.Traducido]).
 * 4. Si no ([Resultado.SinModelo]), quien llama usa la IA como hasta ahora.
 *
 * Si el idioma no está claro (un botón de una palabra) y solo hay un
 * traductor hacia el destino, se usa ese. Solo se tiene abierto un traductor
 * a la vez (cada uno ocupa unos cientos de MB de memoria), y se cierra solo
 * tras un rato sin uso. Nunca se registra el texto.
 */
object Traduccion {
	sealed interface Resultado {
		data class Traducido(val texto: String, val origen: String) : Resultado
		data class MismoIdioma(val idioma: String) : Resultado
		/** No hay traductor descargado para este texto; [origen] es su idioma si se sabe. */
		data class SinModelo(val origen: String?) : Resultado
	}

	private var abierto: TraductorOpus? = null
	private var abiertoId: String? = null

	/** Los traductores descargados hacia [destino]. Mira archivos: mejor fuera del hilo principal. */
	fun instalados(c: Context, destino: String): List<ModelosTraduccion.Modelo> =
		ModelosTraduccion.CATALOGO.filter { it.destino == destino && Recursos.instalado(c, it.recurso) }

	/**
	 * Traduce [texto] al idioma de [destino] si hay un traductor descargado que
	 * sirva. Bloquea (cargar los modelos tarda uno o dos segundos la primera
	 * vez): llámese fuera del hilo principal. [alEmpezar] se llama (en este
	 * mismo hilo) cuando hay traductor y va a empezar, para avisar al usuario.
	 * Si el modelo falla, lanza la excepción (sin el texto) para que quien llama avise.
	 */
	@Synchronized
	fun traducir(c: Context, texto: String, destino: Locale, alEmpezar: () -> Unit = {}): Resultado {
		val d = destino.language
		val pares = instalados(c, d)
		if (pares.isEmpty()) return Resultado.SinModelo(DetectorIdioma.detectar(texto))
		val claro = DetectorIdioma.detectar(texto)
		if (claro == d) return Resultado.MismoIdioma(d)
		val par = if (claro != null) {
			pares.firstOrNull { it.origen == claro } ?: return Resultado.SinModelo(claro)
		} else {
			// Sin pistas claras: el más probable de los que hay (o el destino), o el único traductor
			when (val probable = DetectorIdioma.masProbable(texto, pares.map { it.origen } + d)) {
				d -> return Resultado.MismoIdioma(d)
				null -> pares.singleOrNull() ?: return Resultado.SinModelo(null)
				else -> pares.first { it.origen == probable }
			}
		}
		alEmpezar()
		val traductor = traductor(c, par)
		val traducido = traductor.traducir(texto)
		return Resultado.Traducido(traducido, par.origen)
	}

	/** El traductor de [par], cerrando el que hubiera de otro par. */
	private fun traductor(c: Context, par: ModelosTraduccion.Modelo): TraductorOpus {
		abierto?.let { if (abiertoId == par.id) return it }
		abierto?.let {
			try {
				it.close()
			} catch (e: Exception) {
				Registro.w(ETIQUETA, "No se pudo cerrar el traductor $abiertoId", e)
			}
		}
		Registro.i(ETIQUETA, "Se abre el traductor ${par.id}")
		val hilos = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
		val nuevo = TraductorOpus(Recursos.carpeta(c, par.recurso), Onnx.entorno(), hilos)
		abierto = nuevo
		abiertoId = par.id
		return nuevo
	}

	/** Libera la memoria del traductor abierto (al borrar un modelo o con poca memoria). */
	@Synchronized
	fun cerrar() {
		try {
			abierto?.close()
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo cerrar el traductor $abiertoId", e)
		}
		abierto = null
		abiertoId = null
	}

	/** El nombre de [idioma] («inglés»), en el idioma de [en]. */
	fun nombre(idioma: String, en: Locale): String = Locale(idioma).getDisplayLanguage(en)

	private const val ETIQUETA = "Traduccion"
}
