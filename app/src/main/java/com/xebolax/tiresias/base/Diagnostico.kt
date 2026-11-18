package com.xebolax.tiresias.base

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager

/**
 * Lo que va al final del registro técnico al compartirlo, para entender los fallos de volumen y los choques con
 * otros servicios (SandFly 2aadbe064): las últimas teclas de volumen (cuál, si se quedó en Tiresias o pasó a
 * Android) y los servicios de accesibilidad activos con sus permisos. Nada de lo que hay en la pantalla ni de lo
 * que se escribe: solo teclas de volumen, nunca otras.
 */
object Diagnostico {
	private class Tecla(val momento: Long, val codigo: Int, val accion: Int, val repetida: Int, val quedada: Boolean)

	private val teclas = ArrayDeque<Tecla>()

	/** Apunta una tecla de volumen (las demás no) y si Tiresias se la quedó. */
	@Synchronized
	fun apuntarTecla(e: KeyEvent, quedada: Boolean) {
		if (e.keyCode != KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return
		teclas.addLast(Tecla(SystemClock.uptimeMillis(), e.keyCode, e.action, e.repeatCount, quedada))
		while (teclas.size > MAXIMO_TECLAS) teclas.removeFirst()
	}

	/** El bloque que se añade al registro compartido. */
	@Synchronized
	fun texto(contexto: Context): String {
		val sb = StringBuilder()
		val ahora = SystemClock.uptimeMillis()
		sb.append("\n--- Últimas teclas de volumen ---\n")
		if (teclas.isEmpty()) sb.append("(ninguna)\n")
		for (t in teclas) {
			val tecla = if (t.codigo == KeyEvent.KEYCODE_VOLUME_UP) "subir" else "bajar"
			val accion = if (t.accion == KeyEvent.ACTION_DOWN) "pulsada" else "soltada"
			sb.append("hace ${(ahora - t.momento) / 1000} s: $tecla $accion (repetición ${t.repetida}) → ${if (t.quedada) "Tiresias" else "Android"}\n")
		}
		sb.append("--- Servicios de accesibilidad activos ---\n")
		val am = contexto.getSystemService(AccessibilityManager::class.java)
		val servicios = runCatching { am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty() }.getOrDefault(emptyList())
		if (servicios.isEmpty()) sb.append("(ninguno)\n")
		for (s in servicios) {
			sb.append("${s.id}: flags 0x${Integer.toHexString(s.flags)}, capacidades 0x${Integer.toHexString(s.capabilities)}, eventos 0x${Integer.toHexString(s.eventTypes)}\n")
		}
		return sb.toString()
	}

	private const val MAXIMO_TECLAS = 60
}
