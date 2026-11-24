package com.xebolax.tiresias.base

import android.content.Context
import com.xebolax.tiresias.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * La copia de seguridad de toda la configuración de Tiresias en un archivo
 * JSON, para pasarla a otro móvil o recuperarla: los ajustes (con los gestos y
 * las teclas), los perfiles, las etiquetas, el diccionario de pronunciación,
 * las reparaciones de las pantallas, las apps con toque directo, los temas de
 * sonidos instalados y los sonidos propios, y los complementos con su código y
 * si están activos.
 *
 * Las claves de IA NUNCA van en la copia (están cifradas con una clave del
 * Keystore de este móvil y no servirían en otro): hay que volver a ponerlas.
 * Tampoco van las descargas (voces, modelos), que se descargan otra vez.
 */
object CopiaSeguridad {
	private const val FORMATO = 1
	private const val ETIQUETA = "CopiaSeguridad"

	/**
	 * Ajustes que son de este móvil o de esta instalación, no de la persona. El código
	 * del modo senior va aquí porque el archivo es texto y puede acabar en cualquier
	 * sitio: ni sale en la copia ni una copia antigua que lo lleve lo cambia.
	 */
	private val PROPIOS_DEL_MOVIL = setOf(Claves.VERSION_ANTERIOR, Claves.ULTIMA_BUSQUEDA, Claves.VERSION_OMITIDA, Claves.SENIOR_CODIGO, Claves.ANIMACIONES_ANTES)

	private val ARCHIVOS = listOf("perfiles.json", "etiquetas.json", "diccionario.tsv", "reparaciones.json")

	/** Las preferencias de las apps con toque directo ([com.xebolax.tiresias.gestos.GestosTiresias]). */
	private const val TOQUE_DIRECTO = "toque_directo"

	/** Lo más que ocupan los sonidos en la copia (los temas sacados de Tiresias no van: vienen con él). */
	private const val MAXIMO_SONIDOS = 24L * 1024 * 1024
	private val NOMBRE_CARPETA = Regex("[A-Za-z0-9áéíóúñüÁÉÍÓÚÑÜ_-]{1,60}")
	private val NOMBRE_SONIDO = Regex("[a-z_]{1,40}\\.(ogg|wav|mp3)|tema\\.json")

	/** Lo que Tiresias apunta por su cuenta: no son cambios de nadie. */
	private val SOLOS = listOf("ultima", "ultimo", "servicio_encendido", "ayuda_historial", "ayuda_nuevo_desde")

	fun exportar(c: Context): String {
		val o = JSONObject()
		o.put("tiresias", FORMATO)
		o.put("version", BuildConfig.VERSION_NAME)
		o.put("ajustes", preferencias(Almacen.protegido(c).getSharedPreferences(Ajustes.ARCHIVO, Context.MODE_PRIVATE).all, PROPIOS_DEL_MOVIL))
		val archivos = JSONObject()
		val dir = Almacen.protegido(c).filesDir
		for (nombre in ARCHIVOS) File(dir, nombre).takeIf { it.isFile }?.let { archivos.put(nombre, it.readText()) }
		o.put("archivos", archivos)
		o.put(TOQUE_DIRECTO, preferencias(Almacen.protegido(c).getSharedPreferences(TOQUE_DIRECTO, Context.MODE_PRIVATE).all, emptySet()))
		o.put("sonidos", sonidos(c))
		// Los complementos: su código y si están activos
		val complementos = JSONObject()
		File(c.applicationContext.filesDir, "complementos").listFiles()?.forEach { d ->
			val codigo = File(d, "complemento.js").takeIf { it.isFile }?.readText() ?: return@forEach
			complementos.put(d.name, codigo)
		}
		o.put("complementos", complementos)
		o.put("complementos_ajustes", preferencias(c.applicationContext.getSharedPreferences("complementos", Context.MODE_PRIVATE).all, emptySet()))
		return o.toString(1)
	}

	/** Cuántos ajustes distintos hay entre dos copias de [exportar], sin contar los que cambian solos. */
	fun cambios(antes: String, ahora: String): Int {
		val a = runCatching { JSONObject(antes).optJSONObject("ajustes") }.getOrNull() ?: return 0
		val b = runCatching { JSONObject(ahora).optJSONObject("ajustes") }.getOrNull() ?: return 0
		val claves = (a.keys().asSequence() + b.keys().asSequence()).toSet().filter { c -> SOLOS.none { c.startsWith(it) } }
		return claves.count { a.opt(it)?.toString() != b.opt(it)?.toString() }
	}

	/** Recupera una copia (sustituye lo que hay). Devuelve false si el archivo no es una copia de Tiresias. */
	fun importar(c: Context, texto: String): Boolean {
		val o = runCatching { JSONObject(texto) }.getOrNull() ?: return false
		if (o.optInt("tiresias", 0) !in 1..FORMATO) return false
		val protegido = Almacen.protegido(c)
		o.optJSONObject("ajustes")?.let { poner(protegido.getSharedPreferences(Ajustes.ARCHIVO, Context.MODE_PRIVATE), it, PROPIOS_DEL_MOVIL) }
		o.optJSONObject("archivos")?.let { archivos ->
			for (nombre in ARCHIVOS) {
				val contenido = archivos.optString(nombre, "").takeIf { archivos.has(nombre) } ?: continue
				escribir(File(protegido.filesDir, nombre), contenido)
			}
		}
		o.optJSONObject("complementos")?.let { lista ->
			val carpeta = File(c.applicationContext.filesDir, "complementos")
			for (id in lista.keys()) {
				// Solo un nombre de carpeta, nunca una ruta
				if (!Regex("[A-Za-z0-9._-]{1,80}").matches(id) || id.startsWith(".")) continue
				val d = File(carpeta, id).apply { mkdirs() }
				escribir(File(d, "complemento.js"), lista.getString(id))
			}
		}
		o.optJSONObject("complementos_ajustes")?.let { poner(c.applicationContext.getSharedPreferences("complementos", Context.MODE_PRIVATE), it, emptySet()) }
		o.optJSONObject(TOQUE_DIRECTO)?.let { poner(protegido.getSharedPreferences(TOQUE_DIRECTO, Context.MODE_PRIVATE), it, emptySet()) }
		o.optJSONObject("sonidos")?.let { recuperarSonidos(c, it) }
		return true
	}

	/**
	 * Los temas de sonidos instalados (importados o de la tienda) y los sonidos propios: {"carpeta": {"archivo": base64}}.
	 * Los temas que vienen con Tiresias no van (se sacan de él). Si pasan de [MAXIMO_SONIDOS], los que no caben se quedan fuera.
	 */
	private fun sonidos(c: Context): JSONObject {
		val r = JSONObject()
		var total = 0L
		val carpetas = com.xebolax.tiresias.voz.TemasSonido.carpeta(c).listFiles().orEmpty()
			.filter { it.isDirectory && !it.name.endsWith(".parcial") && it.name != "_incluidos" && NOMBRE_CARPETA.matches(it.name) }
			.sortedBy { if (it.name == "_propios") "" else it.name }
		for (d in carpetas) {
			val archivos = d.listFiles().orEmpty().filter { it.isFile && NOMBRE_SONIDO.matches(it.name) }
			val tamano = archivos.sumOf { it.length() }
			if (total + tamano > MAXIMO_SONIDOS) {
				Registro.w(ETIQUETA, "Un tema de sonidos no cabe en la copia de seguridad (${tamano / 1024} KB)")
				continue
			}
			val o = JSONObject()
			for (f in archivos) o.put(f.name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
			if (o.length() == 0) continue
			total += tamano
			r.put(d.name, o)
		}
		return r
	}

	/** Pone los sonidos de la copia: cada carpeta que trae sustituye a la que haya con su nombre; las demás se quedan. */
	private fun recuperarSonidos(c: Context, o: JSONObject) {
		val base = com.xebolax.tiresias.voz.TemasSonido.carpeta(c)
		for (nombre in o.keys()) {
			// Solo un nombre de carpeta, nunca una ruta
			if (!NOMBRE_CARPETA.matches(nombre) || nombre == "_incluidos") continue
			val archivos = o.optJSONObject(nombre) ?: continue
			val parcial = File(base, "$nombre.parcial").apply {
				deleteRecursively()
				mkdirs()
			}
			try {
				for (archivo in archivos.keys()) {
					if (!NOMBRE_SONIDO.matches(archivo)) continue
					File(parcial, archivo).writeBytes(android.util.Base64.decode(archivos.getString(archivo), android.util.Base64.NO_WRAP))
				}
				val destino = File(base, nombre)
				destino.deleteRecursively()
				if (!parcial.renameTo(destino)) Registro.w(ETIQUETA, "No se pudo poner un tema de sonidos de la copia")
			} catch (e: IllegalArgumentException) {
				Registro.w(ETIQUETA, "Un sonido de la copia no se pudo leer", e)
			} finally {
				parcial.deleteRecursively()
			}
		}
	}

	/** Cada valor con su tipo, para recuperarlo igual: {"clave": {"b": true}}, {"i": 3}, {"s": "texto"}... */
	private fun preferencias(todo: Map<String, *>, fuera: Set<String>): JSONObject {
		val r = JSONObject()
		for ((k, v) in todo) {
			if (k in fuera) continue
			val tipado = when (v) {
				is Boolean -> JSONObject().put("b", v)
				is Int -> JSONObject().put("i", v)
				is Long -> JSONObject().put("l", v)
				is Float -> JSONObject().put("f", v.toDouble())
				is String -> JSONObject().put("s", v)
				is Set<*> -> JSONObject().put("ss", JSONArray(v.map { it.toString() }))
				else -> null
			} ?: continue
			r.put(k, tipado)
		}
		return r
	}

	private fun poner(p: android.content.SharedPreferences, o: JSONObject, fuera: Set<String>) {
		val e = p.edit()
		// Lo que no está en la copia vuelve a su valor por defecto (salvo lo propio del móvil)
		for (k in p.all.keys) if (k !in fuera) e.remove(k)
		for (k in o.keys()) {
			if (k in fuera) continue
			val v = o.optJSONObject(k) ?: continue
			when {
				v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
				v.has("i") -> e.putInt(k, v.getInt("i"))
				v.has("l") -> e.putLong(k, v.getLong("l"))
				v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
				v.has("s") -> e.putString(k, v.getString("s"))
				v.has("ss") -> e.putStringSet(k, v.getJSONArray("ss").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
			}
		}
		e.commit()
	}

	/** Escribe entero o nada: primero a un archivo aparte y luego se cambia por el bueno. */
	private fun escribir(f: File, contenido: String) {
		val parcial = File(f.path + ".parcial")
		parcial.writeText(contenido)
		if (!parcial.renameTo(f)) {
			f.delete()
			parcial.renameTo(f)
		}
	}
}
