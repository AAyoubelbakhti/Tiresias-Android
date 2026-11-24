package com.xebolax.tiresias

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import fyi.quin.backtalk.speech.ISpeechControl
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * La interfaz de voz compatible con la de otros lectores (1.0.20): un teclado o un juego hecho para ellos habla por
 * Tiresias enlazándose a `fyi.quin.backtalk.action.SPEECH_CONTROL`, o con el aviso `fyi.quin.backtalk.action.SPEAK`.
 * La prueba corre en el proceso de Tiresias, así que es la propia app y no se pregunta el permiso.
 */
@RunWith(AndroidJUnit4::class)
class Peticiones120Test : PruebaTiresias() {

	@Test
	fun otraAppHablaPorLaInterfazCompatible() {
		abrirMuestrario()
		val listo = CountDownLatch(1)
		var voz: ISpeechControl? = null
		val conexion = object : ServiceConnection {
			override fun onServiceConnected(nombre: ComponentName?, binder: IBinder?) {
				voz = ISpeechControl.Stub.asInterface(binder)
				listo.countDown()
			}

			override fun onServiceDisconnected(nombre: ComponentName?) {}
		}
		val intent = Intent("fyi.quin.backtalk.action.SPEECH_CONTROL").setPackage(app.packageName)
		try {
			assertNotNull("No se pudo enlazar", app.bindService(intent, conexion, Context.BIND_AUTO_CREATE).takeIf { it })
			listo.await(5, TimeUnit.SECONDS)
			val m = marca()
			voz!!.speak("Hola desde otra app", true, 1f, 1f, 1f)
			assertNotNull("No se dijo lo de la otra app", dichoQue(4000, m) { it.contains("Hola desde otra app") })
		} finally {
			app.unbindService(conexion)
		}
	}

	@Test
	fun otraAppHablaConElAvisoCompatible() {
		assumeTrue(Build.VERSION.SDK_INT >= 34)
		abrirMuestrario()
		val m = marca()
		val i = Intent("fyi.quin.backtalk.action.SPEAK").setPackage(app.packageName)
			.putExtra("text", "Descarga terminada desde otra app").putExtra("interrupt", true)
		app.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
		assertNotNull("No se dijo el aviso", dichoQue(4000, m) { it.contains("Descarga terminada desde otra app") })
	}
}
