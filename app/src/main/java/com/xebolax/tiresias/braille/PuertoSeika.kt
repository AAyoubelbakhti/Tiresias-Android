package com.xebolax.tiresias.braille

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.nucleo.braille.LectorSeika
import com.xebolax.tiresias.nucleo.braille.PaqueteSeika
import com.xebolax.tiresias.nucleo.braille.ProtocoloSeika
import com.xebolax.tiresias.nucleo.braille.TeclaLinea
import com.xebolax.tiresias.nucleo.braille.TeclasSeika
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Una línea braille Seika enchufada por USB, en cualquier versión de Android
 * (no necesita BrailleDisplayController). La línea va detrás de un chip de
 * Silicon Labs que hace de puerto serie: CP210x (10C4:EA60) en las líneas de 40
 * y 80 celdas y CP2110 (10C4:EA80, HID a serie) en los anotadores. Tiresias
 * pone el chip a 9600 baudios, 8 bits, sin paridad y un bit de parada (lo de
 * BRLTTY), pregunta quién es con los tres protocolos ([ProtocoloSeika]) y
 * luego escribe las celdas y lee las teclas.
 *
 * [abrir] e [identificar] bloquean: van en un hilo aparte. Las teclas se leen
 * en su propio hilo y las celdas se escriben en otro, siempre las últimas.
 */
class PuertoSeika private constructor(
	private val usb: UsbManager,
	val dispositivo: UsbDevice,
	private val con: UsbDeviceConnection,
	private val interfaz: UsbInterface,
	private val entrada: UsbEndpoint,
	private val salida: UsbEndpoint,
	/** CP2110: los datos van en informes HID con el largo delante. */
	private val hidASerie: Boolean,
) : ConexionLinea {
	@Volatile private var abierto = true
	private var protocolo = ProtocoloSeika.LINEA
	override var celdas = 0
		private set
	private val escritor = Executors.newSingleThreadExecutor { Thread(it, "Tiresias-Seika-escribir") }
	private val porEscribir = AtomicReference<ByteArray?>()
	private val bufer = ByteArray(maxOf(64, entrada.maxPacketSize))

	/**
	 * Pregunta quién es con cada protocolo (dos veces cada uno, como BRLTTY) y
	 * se queda con el primero que contesta. Devuelve la identidad o null.
	 */
	fun identificar(): PaqueteSeika.Identidad? {
		for (p in ProtocoloSeika.entries) {
			repeat(2) {
				if (!abierto) return null
				vaciarEntrada()
				if (!escribirBytes(p.pedirIdentidad())) return@repeat
				val lector = LectorSeika(p)
				val hasta = SystemClock.uptimeMillis() + ESPERA_IDENTIDAD_MS
				while (SystemClock.uptimeMillis() < hasta) {
					val n = leer(100)
					if (n <= 0) continue
					val id = lector.recibir(bufer.copyOfRange(desde(), desde() + n)).filterIsInstance<PaqueteSeika.Identidad>().firstOrNull()
					if (id != null && id.celdas in 1..160) {
						protocolo = p
						celdas = id.celdas
						this.lector = lector
						Registro.i(ETIQUETA, "Línea Seika: protocolo $p, ${id.celdas} celdas")
						return id
					}
				}
			}
		}
		return null
	}

	private var lector: LectorSeika? = null

	/**
	 * Empieza a leer las teclas en un hilo propio: cada combinación llega a
	 * [alTeclas] (desde ese hilo) y después un conjunto vacío, porque las Seika
	 * mandan la combinación entera al soltarla. [alCortarse] si el USB deja de responder.
	 */
	fun empezar(alTeclas: (Set<TeclaLinea>) -> Unit, alCortarse: () -> Unit) {
		val l = lector ?: return
		Thread({
			var fallos = 0
			while (abierto) {
				val antes = SystemClock.uptimeMillis()
				val n = leer(500)
				if (n > 0) {
					fallos = 0
					for (paquete in l.recibir(bufer.copyOfRange(desde(), desde() + n))) {
						if (paquete !is PaqueteSeika.Teclas) continue
						val t = TeclasSeika.teclas(protocolo, paquete)
						if (t.isEmpty()) continue
						alTeclas(t)
						alTeclas(emptySet())
					}
				} else if (SystemClock.uptimeMillis() - antes < 20) {
					// Un error al momento (no una espera sin datos): el USB ya no está
					if (++fallos > 50) {
						if (abierto) alCortarse()
						break
					}
					SystemClock.sleep(20)
				}
			}
		}, "Tiresias-Seika-leer").apply { isDaemon = true }.start()
	}

	/** Pone [contenido] en la línea (en otro hilo; si se acumulan, solo las últimas). */
	override fun escribir(contenido: ByteArray): Boolean {
		if (!abierto || celdas == 0) return false
		porEscribir.set(protocolo.celdas(celdas, contenido))
		runCatching {
			escritor.execute {
				val p = porEscribir.getAndSet(null) ?: return@execute
				if (!escribirBytes(p)) Registro.w(ETIQUETA, "No se pudo escribir en la línea Seika")
			}
		}.onFailure { return false }
		return true
	}

	override fun sigue(): Boolean = abierto && usb.deviceList.values.any { it.deviceName == dispositivo.deviceName }

	override fun cerrar() {
		if (!abierto) return
		abierto = false
		escritor.shutdownNow()
		runCatching { con.releaseInterface(interfaz) }
		runCatching { con.close() }
	}

	// --- El chip --------------------------------------------------------------------------------

	/** Donde empiezan los datos en [bufer]: en el CP2110, tras el byte del largo. */
	private fun desde() = if (hidASerie) 1 else 0

	/** Lee lo que haya (espera como mucho [ms]); devuelve cuántos bytes de datos. */
	private fun leer(ms: Int): Int {
		val n = con.bulkTransfer(entrada, bufer, bufer.size, ms)
		if (n <= 0) return n
		if (!hidASerie) return n
		// CP2110: el primer byte es el identificador del informe, que es el número de bytes que trae
		val largo = bufer[0].toInt() and 0xFF
		return minOf(largo, n - 1)
	}

	private fun vaciarEntrada() {
		repeat(8) { if (leer(20) <= 0) return }
	}

	private fun escribirBytes(datos: ByteArray): Boolean {
		if (!abierto) return false
		if (!hidASerie) return con.bulkTransfer(salida, datos, datos.size, 1000) == datos.size
		// CP2110: trozos de 63 bytes, cada uno en un informe cuyo identificador es su largo
		var i = 0
		while (i < datos.size) {
			val n = minOf(63, datos.size - i)
			val informe = ByteArray(n + 1)
			informe[0] = n.toByte()
			datos.copyInto(informe, 1, i, i + n)
			if (con.bulkTransfer(salida, informe, informe.size, 1000) < 0) return false
			i += n
		}
		return true
	}

	companion object {
		private const val ETIQUETA = "PuertoSeika"
		private const val SILICON_LABS = 0x10C4
		/** CP210x: las líneas Seika de 40 y 80 celdas. */
		private const val CP210X = 0xEA60
		/** CP2110: los anotadores Seika (Seika Mini). */
		private const val CP2110 = 0xEA80
		private const val ESPERA_IDENTIDAD_MS = 400L
		private const val BAUDIOS = 9600

		fun esSeika(d: UsbDevice): Boolean = d.vendorId == SILICON_LABS && (d.productId == CP210X || d.productId == CP2110)

		/** Abre el dispositivo y prepara el chip (con permiso ya dado). Bloquea; null si no se puede. */
		fun abrir(usb: UsbManager, d: UsbDevice): PuertoSeika? {
			val hid = d.productId == CP2110
			val tipo = if (hid) UsbConstants.USB_ENDPOINT_XFER_INT else UsbConstants.USB_ENDPOINT_XFER_BULK
			var interfaz: UsbInterface? = null
			var entrada: UsbEndpoint? = null
			var salida: UsbEndpoint? = null
			for (i in 0 until d.interfaceCount) {
				val f = d.getInterface(i)
				var e: UsbEndpoint? = null
				var s: UsbEndpoint? = null
				for (k in 0 until f.endpointCount) {
					val p = f.getEndpoint(k)
					if (p.type != tipo) continue
					if (p.direction == UsbConstants.USB_DIR_IN) e = e ?: p else s = s ?: p
				}
				if (e != null && s != null) {
					interfaz = f; entrada = e; salida = s
					break
				}
			}
			if (interfaz == null || entrada == null || salida == null) {
				Registro.w(ETIQUETA, "El chip de la línea Seika no tiene las vías esperadas (${d.interfaceCount} interfaces)")
				return null
			}
			val con = runCatching { usb.openDevice(d) }.getOrNull() ?: run {
				Registro.w(ETIQUETA, "Android no deja abrir la línea Seika")
				return null
			}
			// (force: el CP2110 es HID y Android lo tiene cogido para sí)
			if (!con.claimInterface(interfaz, true)) {
				Registro.w(ETIQUETA, "No se pudo coger la interfaz de la línea Seika")
				con.close()
				return null
			}
			val ok = if (hid) prepararCp2110(con, interfaz.id) else prepararCp210x(con, interfaz.id)
			if (!ok) {
				Registro.w(ETIQUETA, "No se pudo preparar el chip de la línea Seika")
				runCatching { con.releaseInterface(interfaz) }
				con.close()
				return null
			}
			return PuertoSeika(usb, d, con, interfaz, entrada, salida, hid)
		}

		/** CP210x: encender, 9600 baudios, 8N1, DTR y RTS (órdenes de fabricante a la interfaz, AN571). */
		private fun prepararCp210x(con: UsbDeviceConnection, i: Int): Boolean {
			fun orden(peticion: Int, valor: Int, datos: ByteArray? = null) =
				con.controlTransfer(0x41, peticion, valor, i, datos, datos?.size ?: 0, 1000) >= 0
			if (!orden(0x00, 0x0001)) return false
			// La velocidad: con el valor directo (CP2102 y posteriores) o con el divisor (el CP2101 antiguo)
			val baudios = byteArrayOf((BAUDIOS and 0xFF).toByte(), (BAUDIOS shr 8 and 0xFF).toByte(), (BAUDIOS shr 16 and 0xFF).toByte(), 0)
			if (!orden(0x1E, 0, baudios) && !orden(0x01, 0x384000 / BAUDIOS)) return false
			if (!orden(0x03, 0x0800)) return false
			orden(0x07, 0x0303)
			// Vaciar lo que hubiera en el chip
			orden(0x12, 0x000F)
			return true
		}

		/** CP2110: encender la UART y ponerla a 9600 8N1 (informes de característica HID, AN434). */
		private fun prepararCp2110(con: UsbDeviceConnection, i: Int): Boolean {
			fun informe(datos: ByteArray) =
				con.controlTransfer(0x21, 0x09, 0x0300 or (datos[0].toInt() and 0xFF), i, datos, datos.size, 1000) >= 0
			if (!informe(byteArrayOf(0x41, 0x01))) return false
			val config = byteArrayOf(0x50, (BAUDIOS ushr 24).toByte(), (BAUDIOS ushr 16 and 0xFF).toByte(), (BAUDIOS ushr 8 and 0xFF).toByte(), (BAUDIOS and 0xFF).toByte(), 0, 0, 3, 0)
			if (!informe(config)) return false
			// Vaciar las colas de entrada y salida
			informe(byteArrayOf(0x43, 0x03))
			return true
		}
	}
}

/** Una línea de verdad conectada (HID o Seika): sus celdas y cómo se le escribe. */
interface ConexionLinea {
	val celdas: Int
	/** Pone las celdas en la línea; false si no se ha podido. */
	fun escribir(contenido: ByteArray): Boolean
	/** Si sigue conectada (tras desenchufar algo o un error al escribir). */
	fun sigue(): Boolean
	fun cerrar()
}
