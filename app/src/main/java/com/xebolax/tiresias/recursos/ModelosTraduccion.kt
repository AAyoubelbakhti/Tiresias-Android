package com.xebolax.tiresias.recursos

import com.xebolax.tiresias.R
import com.xebolax.tiresias.traduccion.TraductorOpus

/**
 * Los traductores sin conexión que se pueden descargar: modelos OPUS-MT de
 * Helsinki-NLP (CC BY 4.0) convertidos a ONNX por Xenova, cuantizados. Uno por
 * par y sentido (de inglés a español no traduce de español a inglés), unos
 * 110 a 140 MB cada uno. Funcionan sin Internet una vez bajados
 * ([com.xebolax.tiresias.traduccion.Traduccion]).
 *
 * El tamaño y la huella SHA-256 de cada archivo salen de la API de Hugging Face
 * (los ONNX son LFS) o de bajarlo (el tokenizador y la configuración).
 */
object ModelosTraduccion {
	data class Modelo(
		val origen: String,
		val destino: String,
		val codificador: Long,
		val shaCodificador: String,
		val decodificador: Long,
		val shaDecodificador: String,
		val tokenizador: Long,
		val shaTokenizador: String,
		val configuracion: Long,
		val shaConfiguracion: String,
	) {
		val id: String get() = "opus_${origen}_$destino"

		val recurso: Recursos.Recurso
			get() {
				val base = "$HF/Xenova/opus-mt-$origen-$destino/resolve/main"
				return Recursos.Recurso(
					id, R.string.recurso_traductor, R.string.recurso_traductor_desc,
					listOf(
						Recursos.Archivo("$base/onnx/encoder_model_quantized.onnx", TraductorOpus.CODIFICADOR, codificador, shaCodificador),
						Recursos.Archivo("$base/onnx/decoder_model_merged_quantized.onnx", TraductorOpus.DECODIFICADOR, decodificador, shaDecodificador),
						Recursos.Archivo("$base/tokenizer.json", TraductorOpus.TOKENIZADOR, tokenizador, shaTokenizador),
						Recursos.Archivo("$base/config.json", TraductorOpus.CONFIGURACION, configuracion, shaConfiguracion),
					),
					LICENCIA,
				)
			}
	}

	private const val HF = "https://huggingface.co"
	private const val LICENCIA = "CC BY 4.0 (Helsinki-NLP, OPUS-MT)"

	val CATALOGO = listOf(
		// Hacia el español
		Modelo("en", "es",
			52_899_742, "03434edd1147e6c49e22066e9dc5888235e99cc35a6711d1c4ea5b06455d5da9",
			60_212_804, "58ee5ddd6e22d1693d8722b90c1486afb93700a4376cf28fd74175052ad530ae",
			6_262_682, "285eb29e7155ee48851a77960797813f86a125f70d2c1a124f613f1fbd2b19c3",
			1_468, "499eae1f97eeba63172fc884f92fd727ae803b7ceb79c0f653aa4b011152af2f"),
		Modelo("fr", "es",
			57_928_094, "946954149b0179a0fb60145a3ed85da2f58868ef2e6a442a1cb4301563b70ac3",
			65_280_440, "ff991f24d534a62db615697ed9555db80be28fbf9e5677d8ebb1f78c6aabc2c6",
			7_583_355, "af3d7f6d5225f092ef551d182b99866decac113fba0b4bfc820d505e376fc9ad",
			1_376, "20fb838043a5bd522abc2582149dfa2a0e45debe298098ef147b4b525de4a2c0"),
		Modelo("de", "es",
			51_005_342, "54a64cdcdef126647fe4922b33744f6643982084ee0a58b6d441bec5d17f7deb",
			58_303_604, "8b426d5cafcc60359eeb3577d4becd021be84c4a9eefabef811a6b0dd10e0082",
			5_944_958, "da29e101fb1b189976f017d7c21f87d2435f2c49f6fdd46ea83999d6443f3f5d",
			1_376, "64212746d9d77ad818ecb47faaa04678aa1b21f85153ca2c11ef6086bd0cfc0f"),
		Modelo("it", "es",
			49_843_102, "3e0ef961235dc192d99555fe015ac0c28c0a93d006e98e6050a13dc45f45bb88",
			57_132_284, "147aa9f62b543cb7a7a119aabc89ce26b7defab212fb2486b2bff94e09986dce",
			5_711_996, "157d59dd3f76ccb4b4fde84d42c518732dcceefd861a75c6fd507c580646459c",
			1_376, "d5ab0dee386c08e3d6cc7c497a08a663f7da3dfcbe7356e3faf47f4517d0fe88"),
		// Hacia el inglés
		Modelo("es", "en",
			52_899_742, "c01e70f8455efc350831aa2af7ed187b16829241f0018ba07d1ba643a391bc18",
			60_212_804, "4cd91ab30240d295ce907b5100826031838c672005e57e524d711122d75605fa",
			6_262_682, "285eb29e7155ee48851a77960797813f86a125f70d2c1a124f613f1fbd2b19c3",
			1_433, "fab3a7f93185bc5aa7b419f6a1e6e74d98c8f2a506c94493d3019bf46da3478d"),
		Modelo("fr", "en",
			50_090_398, "e727cb26ac6bf816394c49671af69c9ae5798868fed1e40849872415dffc1772",
			57_381_512, "73bc7ac8e29c42e6f212ebcc29a2991d3646a04aabb642c0036aa54b40e4e1a9",
			5_637_839, "8391785c1a2139e7af4678571ccd8dc654ecbb72e4be186940f65d7c604f0246",
			1_411, "6c2851f154d7b88c7767e4fdcf8a5694a36a456bfa6cbcde1b13fdbade3b56f6"),
		Modelo("de", "en",
			49_366_942, "4cedda8f8c89b72a42b3c6cd1e7a27f2de24457093e3bf80cb3e46829641fcd8",
			56_652_404, "4712800584b2d77c2f9e620bf29523e2b20b62f65ade5011ee01780e2b0ea5d3",
			5_498_450, "8e0fcf45621ea87fa680c7f9969c37a7f819c1f4c7658a2e6e0879b866a14b17",
			1_376, "6754743ec4754957d8552dad869d1314f3af6626b67446ec4cebe5e2fbaa0fc4"),
		Modelo("it", "en",
			60_773_278, "a025d2d85c90da23880116b67175e43e71921d8d2bf446b9f1eacdc86015aa4c",
			68_147_852, "aebc2789cd2ef8062fbda020bac90b61e68458ba98a4c43cea63c1d2310ba2b8",
			7_942_314, "c19e1dac8287ec5c5d47a0d81be5e0fd11755666b996c764ef4d13bb8117e3a2",
			1_376, "4b82a23b7a91b213a934db70d0a33712a739fdd7c5686b02d89642d7dcfc7ac4"),
	)

	fun de(origen: String, destino: String): Modelo? = CATALOGO.firstOrNull { it.origen == origen && it.destino == destino }
}
