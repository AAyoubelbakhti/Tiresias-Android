package com.xebolax.tiresias.braille

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.xebolax.tiresias.R
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.lectura.Lector
import com.xebolax.tiresias.nucleo.braille.OrdenLinea
import com.xebolax.tiresias.nucleo.braille.TablaBraille
import com.xebolax.tiresias.nucleo.braille.TeclaLinea
import com.xebolax.tiresias.nucleo.braille.TecladoLinea
import com.xebolax.tiresias.nucleo.braille.VistaLinea
import com.xebolax.tiresias.voz.Sonido
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * La línea braille: la busca, se conecta y le pone lo que hay que ver. Hay dos
 * formas de llegar a ella:
 * - Las líneas Seika por USB ([PuertoSeika]), en cualquier Android: Tiresias
 *   habla con su chip directamente.
 * - Las líneas HID Braille por USB o Bluetooth ([LineasHid]), con
 *   BrailleDisplayController de Android 15 o posterior.
 *
 * Todo pasa en el hilo del lector. Las teclas se convierten en órdenes
 * ([TecladoLinea]) que hace [Braille].
 */
class LineaBraille(private val lector: Lector, private val alOrden: (OrdenLinea) -> Unit, private val alCambiarConexion: () -> Unit) {
	private val servicio = lector.servicio
	private val enHilo = Executor { lector.manejador.post(it) }
	private val usb: UsbManager? = runCatching { servicio.getSystemService(UsbManager::class.java) }.getOrNull()
	/** Las líneas HID (Android 15 o posterior); null antes. */
	private val hid: LineasHid? = if (Build.VERSION.SDK_INT >= 35) LineasHid(servicio, enHilo).takeIf { it.disponible } else null
	/** Abre y prepara las Seika, que bloquea. */
	private val hiloSeika = Executors.newSingleThreadExecutor { Thread(it, "Tiresias-Seika-abrir") }
	/** Las combinaciones de la línea, con las que haya cambiado el usuario. */
	@Volatile private var acordes = TecladoLinea.leerAcordes(lector.ajustes.acordesBraille)
	private val teclado = TecladoLinea { acordes }

	/** Las combinaciones o el cursor han cambiado en Ajustes. */
	fun aplicarAjustes() {
		acordes = TecladoLinea.leerAcordes(lector.ajustes.acordesBraille)
		vista?.puntosCursor = lector.ajustes.puntosCursorBraille
	}

	/** La línea de verdad que está conectada. */
	private var conexion: ConexionLinea? = null
	var vista: VistaLinea? = null
		private set

	/** Los dispositivos que quedan por probar en esta búsqueda y si se avisa de lo que pase. */
	private val candidatos = ArrayDeque<Any>()
	private var avisarBusqueda = false
	private var probando = false

	/** Un mensaje (una notificación, un anuncio) se ve un rato y luego vuelve lo que había. */
	private var mensajeHasta = 0L
	private val quitarMensaje = Runnable { escribir() }

	val conectada: Boolean get() = conexion != null || virtual != null

	/** Una línea que es otra app (API): sus celdas y cómo mandarle lo que hay que ver. */
	private class Virtual(val nombre: String, val celdas: Int, val enviar: (ByteArray) -> Unit)
	private var virtual: Virtual? = null

	private val celdasLinea: Int get() = conexion?.celdas ?: virtual?.celdas ?: 0

	/** Otra app hace de línea (un anotador, el programa del fabricante); solo si no hay una línea de verdad. */
	fun conectarVirtual(nombre: String, celdas: Int, enviar: (ByteArray) -> Unit): Boolean {
		if (conexion != null || celdas !in 1..160) return false
		virtual = Virtual(nombre, celdas, enviar)
		vista = VistaLinea(celdas).apply { puntosCursor = lector.ajustes.puntosCursorBraille }
		lector.sonidos.tocar(Sonido.ENCENDIDO)
		lector.decir(lector.textos.getString(R.string.braille_linea_virtual, nombre, celdas))
		lector.manejador.postDelayed({ alCambiarConexion() }, 2000)
		lector.foco?.let { lector.braille.mostrarElemento(it) }
		return true
	}

	fun desconectarVirtual() {
		if (virtual == null) return
		virtual = null
		alDesconectar(true)
	}

	private val receptor = object : BroadcastReceiver() {
		override fun onReceive(c: Context, i: Intent) {
			when (i.action) {
				UsbManager.ACTION_USB_DEVICE_ATTACHED -> lector.manejador.postDelayed({ buscar(false) }, 800)
				UsbManager.ACTION_USB_DEVICE_DETACHED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> lector.manejador.post { comprobarConexion() }
				// La parte HID de Bluetooth tarda un poco más que el enlace en estar lista
				BluetoothDevice.ACTION_ACL_CONNECTED -> lector.manejador.postDelayed({ if (!conectada) buscar(false) }, 2500)
				PERMISO_USB -> if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) lector.manejador.post { buscar(true) }
			}
		}
	}

	fun iniciar() {
		if (usb == null && hid == null) return
		val filtro = IntentFilter().apply {
			addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
			addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
			addAction(PERMISO_USB)
			if (hid != null) {
				addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
				addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
			}
		}
		runCatching { ContextCompat.registerReceiver(servicio, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED) }
			.onFailure { Registro.w(ETIQUETA, "No se pudo escuchar las conexiones de las líneas", it) }
		lector.manejador.postDelayed({ buscar(false) }, 1500)
	}

	fun parar() {
		runCatching { servicio.unregisterReceiver(receptor) }
		desconectar()
	}

	fun desconectar() {
		conexion?.cerrar()
		hid?.soltar()
		conexion = null
		vista = null
	}

	/**
	 * Busca una línea entre las Seika y los dispositivos USB HID enchufados y
	 * los Bluetooth emparejados, y se conecta a la primera que lo sea.
	 * [avisar]: dice lo que pasa (cuando lo pide el usuario); si no, solo avisa al conectar.
	 */
	fun buscar(avisar: Boolean) {
		if (conectada || probando) {
			if (avisar && conectada) lector.decir(R.string.braille_linea_conectada, celdasLinea)
			return
		}
		avisarBusqueda = avisar
		candidatos.clear()
		candidatos.addAll(dispositivosUsb(avisar))
		if (hid != null) candidatos.addAll(dispositivosBluetooth(avisar))
		if (candidatos.isEmpty()) {
			if (avisar) lector.decir(if (hid == null) R.string.braille_linea_ninguna_antigua else R.string.braille_linea_ninguna)
			return
		}
		if (avisar) lector.decir(R.string.braille_linea_buscando)
		probarSiguiente()
	}

	/** Las Seika primero (Tiresias habla con ellas directamente) y luego las HID, si hay BrailleDisplayController. */
	private fun dispositivosUsb(pedirPermiso: Boolean): List<UsbDevice> {
		val u = usb ?: return emptyList()
		val todos = runCatching { u.deviceList.values.toList() }.getOrDefault(emptyList())
		val seika = todos.filter { PuertoSeika.esSeika(it) }
		val hidUsb = if (hid == null) emptyList() else todos.filter { d -> !PuertoSeika.esSeika(d) && (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID } }
		val posibles = seika + hidUsb
		val conPermiso = posibles.filter { u.hasPermission(it) }
		// Sin permiso, Android pregunta al usuario (una vez por dispositivo); al aceptarlo se vuelve a buscar
		if (pedirPermiso || conPermiso.isEmpty()) {
			posibles.firstOrNull { !u.hasPermission(it) }?.let { d ->
				val banderas = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
				val pi = PendingIntent.getBroadcast(servicio, 0, Intent(PERMISO_USB).setPackage(servicio.packageName), banderas)
				runCatching { u.requestPermission(d, pi) }.onFailure { Registro.w(ETIQUETA, "No se pudo pedir permiso para el USB", it) }
				// Lo que sale es una ventana de Android: se avisa para que no pille por sorpresa
				if (PuertoSeika.esSeika(d)) lector.decir(R.string.braille_linea_permiso_usb)
			}
		}
		return conPermiso
	}

	private fun dispositivosBluetooth(avisar: Boolean): List<BluetoothDevice> {
		if (servicio.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
			if (avisar) lector.decir(R.string.braille_linea_permiso_bt)
			return emptyList()
		}
		val adaptador = servicio.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
		val emparejados = runCatching { adaptador.bondedDevices.toList() }.getOrDefault(emptyList())
		// Primero los que por el nombre parecen líneas braille; los demás también se prueban (falla enseguida si no lo son)
		return emparejados.sortedByDescending { d -> runCatching { d.name }.getOrNull()?.let { NOMBRES.containsMatchIn(it) } == true }
	}

	/** Lo que pasa con el dispositivo que se está probando; todo en el hilo del lector. */
	private val oyente = object : LineasHid.Oyente {
		override fun alConectar(c: ConexionLinea) = this@LineaBraille.alConectar(c)
		override fun alFallar() = probarSiguiente()
		override fun alTeclas(t: Set<TeclaLinea>) = this@LineaBraille.alTeclas(t)
		override fun alDesconectar() = this@LineaBraille.alDesconectar()
	}

	private fun probarSiguiente() {
		val d = candidatos.removeFirstOrNull()
		if (d == null) {
			probando = false
			if (avisarBusqueda && !conectada) lector.decir(R.string.braille_linea_ninguna)
			return
		}
		probando = true
		if (d is UsbDevice && PuertoSeika.esSeika(d)) probarSeika(d) else hid?.probar(d, oyente) ?: probarSiguiente()
	}

	private fun probarSeika(d: UsbDevice) {
		val u = usb ?: return probarSiguiente()
		val ok = runCatching {
			hiloSeika.execute {
				val p = runCatching { PuertoSeika.abrir(u, d) }.onFailure { Registro.w(ETIQUETA, "No se pudo abrir la línea Seika", it) }.getOrNull()
				val id = p?.let { runCatching { it.identificar() }.onFailure { e -> Registro.w(ETIQUETA, "La línea Seika no contesta", e) }.getOrNull() }
				lector.manejador.post {
					if (p == null || id == null) {
						if (p != null) Registro.i(ETIQUETA, "La línea Seika no ha dicho quién es")
						p?.cerrar()
						probarSiguiente()
						return@post
					}
					alConectar(p)
					p.empezar(
						alTeclas = { t -> lector.manejador.post { if (conexion === p) alTeclas(t) } },
						alCortarse = { lector.manejador.post { if (conexion === p) alDesconectar() } },
					)
				}
			}
		}
		if (ok.isFailure) probarSiguiente()
	}

	private fun alConectar(c: ConexionLinea) {
		probando = false
		candidatos.clear()
		conexion = c
		vista = VistaLinea(c.celdas).apply { puntosCursor = lector.ajustes.puntosCursorBraille }
		lector.sonidos.tocar(Sonido.ENCENDIDO)
		lector.decir(R.string.braille_linea_conectada, c.celdas)
		// «Primero braille» se aplica cuando ya se ha dicho que está conectada
		lector.manejador.postDelayed({ alCambiarConexion() }, 2000)
		// Lo que tiene el foco, en la línea desde el primer momento
		lector.foco?.let { lector.braille.mostrarElemento(it) }
	}

	private fun alDesconectar(estabaVirtual: Boolean = false) {
		val estaba = conectada || estabaVirtual
		conexion?.cerrar()
		conexion = null
		vista = null
		probando = false
		// Lo primero, devolver la voz (si estaba callada por «primero braille»)
		alCambiarConexion()
		if (estaba) {
			lector.sonidos.tocar(Sonido.APAGADO)
			lector.decir(R.string.braille_linea_desconectada)
		}
	}

	private fun comprobarConexion() {
		val c = conexion ?: return
		if (!c.sigue()) alDesconectar()
	}

	private fun alTeclas(teclas: Set<TeclaLinea>) {
		if (conexion == null) return
		teclado.alTeclas(teclas)?.let { alOrden(it) }
	}

	/** Pone un texto en la línea (con cursor en los campos de texto). */
	fun mostrar(tabla: TablaBraille, texto: String, cursor: Int = -1) {
		val v = vista ?: return
		v.poner(tabla.traducir(texto), cursor)
		if (System.currentTimeMillis() < mensajeHasta) return
		escribir()
	}

	/** El cursor se movió en el texto que ya se ve. */
	fun moverCursor(cursor: Int) {
		val v = vista ?: return
		v.moverCursor(cursor)
		if (System.currentTimeMillis() >= mensajeHasta) escribir()
	}

	/** Un mensaje que se ve [ms] milisegundos y luego vuelve lo que había. */
	fun mensaje(tabla: TablaBraille, texto: String, ms: Long) {
		val n = celdasLinea
		if (n == 0) return
		val temporal = VistaLinea(n).apply { poner(tabla.traducir(texto)) }
		mensajeHasta = System.currentTimeMillis() + ms
		enviar(temporal.celdas())
		lector.manejador.removeCallbacks(quitarMensaje)
		lector.manejador.postDelayed(quitarMensaje, ms)
	}

	/** Desplaza la línea por el texto; false si ya se ve el final (o el principio). */
	fun desplazar(adelante: Boolean): Boolean {
		val v = vista ?: return false
		mensajeHasta = 0
		val movido = if (adelante) v.avanzar() else v.retroceder()
		if (movido) escribir()
		return movido
	}

	private fun escribir() {
		mensajeHasta = 0
		vista?.let { enviar(it.celdas()) }
	}

	private fun enviar(celdas: ByteArray) {
		virtual?.let { v ->
			runCatching { v.enviar(celdas) }.onFailure { Registro.w(ETIQUETA, "La línea de ${v.nombre} no contesta", it) }
			return
		}
		val c = conexion ?: return
		if (!c.escribir(celdas)) comprobarConexion()
	}

	companion object {
		private const val ETIQUETA = "LineaBraille"
		private const val PERMISO_USB = "com.xebolax.tiresias.PERMISO_USB_BRAILLE"
		/** Nombres Bluetooth de las líneas más comunes (solo para probarlas antes). */
		private val NOMBRES = Regex("(?i)braille|focus|brailliant|orbit|mantis|chameleon|activator|actilino|basic braille|esys|esytime|hims|qbraille|braille ?sense|brailleedge|bnote|nlS|apex|vario|seika|caiku|refreshabraille|pronto|alva|easy ?link|bbe|hid ?braille")
	}
}
