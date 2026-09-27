package billetera;

import static billetera.BilleteraServicioTest.llave;
import static billetera.BilleteraServicioTest.pesos;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/** Los cobros divididos por HTTP, como los usaría una app. */
@PruebaDeIntegracion
class CobrosApiTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void laPlataCuadra() {
		VerificadorDePlata.verificar(jdbc);
	}

	@Test
	void flujoCompletoDeLaCena() {
		String ana = crearCuenta("Ana");
		String beto = crearCuentaConSaldo("Beto", pesos(50_000));
		String caro = crearCuentaConSaldo("Caro", pesos(50_000));
		String dani = crearCuentaConSaldo("Dani", pesos(50_000));

		MvcTestResult creado = crearCobro(ana, pesos(100_000), "La cena", llave(), beto, caro, dani);
		assertThat(creado).hasStatus(HttpStatus.CREATED);
		String cobro = JsonPath.read(cuerpo(creado), "$.id");
		List<Number> montos = JsonPath.read(cuerpo(creado), "$.cuotas[*].montoCentavos");
		assertThat(montos).extracting(Number::longValue).containsExactly(3_333_334L, 3_333_333L, 3_333_333L);

		// Beto ve lo que debe y lo paga.
		String debeBeto = cuerpo(mvc.get().uri("/cuentas/{id}/cuotas-pendientes", beto).exchange());
		String cuotaBeto = JsonPath.read(debeBeto, "$[0].cuotaId");
		assertThat((String) JsonPath.read(debeBeto, "$[0].cobradorTitular")).isEqualTo("Ana");
		assertThat(pagar(cuotaBeto, llave())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.estadoCobro")
			.isEqualTo("ABIERTO");

		// Ana ve quién pagó y quién falta.
		String detalle = cuerpo(mvc.get().uri("/cobros/{id}", cobro).exchange());
		List<String> estados = JsonPath.read(detalle, "$.cuotas[*].estado");
		assertThat(estados).containsExactly("PAGADA", "PENDIENTE", "PENDIENTE");

		// Pagan los demás: el cobro queda completo.
		pagar(cuotaDe(detalle, caro), llave());
		assertThat(pagar(cuotaDe(detalle, dani), llave())).bodyJson()
			.extractingPath("$.estadoCobro")
			.isEqualTo("COMPLETADO");
		assertThat(saldo(ana)).isEqualTo(pesos(100_000));
	}

	@Test
	void pagarDosVecesResponde409() {
		String ana = crearCuenta("Ana");
		String beto = crearCuentaConSaldo("Beto", pesos(100));
		String detalle = cuerpo(crearCobro(ana, pesos(30), "La cena", llave(), beto));
		String cuota = cuotaDe(detalle, beto);
		pagar(cuota, llave());

		assertThat(pagar(cuota, llave())).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("ya está pagada");
		assertThat(saldo(beto)).isEqualTo(pesos(70));
	}

	@Test
	void pagarSinFondosResponde422() {
		String ana = crearCuenta("Ana");
		String beto = crearCuenta("Beto");
		String detalle = cuerpo(crearCobro(ana, pesos(30), "La cena", llave(), beto));

		assertThat(pagar(cuotaDe(detalle, beto), llave())).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void pagarSinIdempotencyKeyResponde400() {
		String ana = crearCuenta("Ana");
		String beto = crearCuentaConSaldo("Beto", pesos(100));
		String detalle = cuerpo(crearCobro(ana, pesos(30), "La cena", llave(), beto));

		assertThat(mvc.post().uri("/cuotas/{id}/pago", cuotaDe(detalle, beto))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(saldo(beto)).isEqualTo(pesos(100));
	}

	@Test
	void cancelarYLuegoPagarResponde409() {
		String ana = crearCuenta("Ana");
		String beto = crearCuentaConSaldo("Beto", pesos(100));
		String detalle = cuerpo(crearCobro(ana, pesos(30), "La cena", llave(), beto));
		String cobro = JsonPath.read(detalle, "$.id");

		assertThat(mvc.post().uri("/cobros/{id}/cancelacion", cobro)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.estado")
			.isEqualTo("CANCELADO");
		assertThat(pagar(cuotaDe(detalle, beto), llave())).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void cobrarseASiMismoResponde400() {
		String ana = crearCuenta("Ana");

		assertThat(crearCobro(ana, pesos(30), "La cena", llave(), ana)).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void cobroQueNoExisteResponde404() {
		assertThat(mvc.get().uri("/cobros/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.post().uri("/cuotas/{id}/pago", UUID.randomUUID()).header("Idempotency-Key", llave()))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void reintentarLaCreacionDevuelveElMismoCobro() {
		String ana = crearCuenta("Ana");
		String beto = crearCuenta("Beto");
		String llave = llave();

		String primero = JsonPath.read(cuerpo(crearCobro(ana, pesos(30), "La cena", llave, beto)), "$.id");
		String reintento = JsonPath.read(cuerpo(crearCobro(ana, pesos(30), "La cena", llave, beto)), "$.id");

		assertThat(reintento).isEqualTo(primero);
		List<String> cobrosDeAna = JsonPath.read(cuerpo(mvc.get().uri("/cuentas/{id}/cobros", ana).exchange()), "$[*].id");
		assertThat(cobrosDeAna).containsExactly(primero);
	}

	// ---------- ayudas ----------

	private String crearCuenta(String titular) {
		return JsonPath.read(cuerpo(mvc.post().uri("/cuentas").contentType(MediaType.APPLICATION_JSON).content("""
				{"titular": "%s"}""".formatted(titular)).exchange()), "$.id");
	}

	private String crearCuentaConSaldo(String titular, long centavos) {
		String id = crearCuenta(titular);
		mvc.post().uri("/cuentas/{id}/cargas", id).header("Idempotency-Key", llave())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"montoCentavos": %d}""".formatted(centavos))
			.exchange();
		return id;
	}

	private MvcTestResult crearCobro(String cobrador, long totalCentavos, String descripcion, String llave,
			String... deudores) {
		String lista = String.join(", ", java.util.Arrays.stream(deudores).map(d -> "\"" + d + "\"").toList());
		return mvc.post().uri("/cobros").header("Idempotency-Key", llave)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"cobradorId": "%s", "totalCentavos": %d, "descripcion": "%s", "deudores": [%s]}"""
				.formatted(cobrador, totalCentavos, descripcion, lista))
			.exchange();
	}

	private MvcTestResult pagar(String cuotaId, String llave) {
		return mvc.post().uri("/cuotas/{id}/pago", cuotaId).header("Idempotency-Key", llave).exchange();
	}

	private long saldo(String cuentaId) {
		return JsonPath.<Number>read(cuerpo(mvc.get().uri("/cuentas/{id}", cuentaId).exchange()), "$.saldoCentavos")
			.longValue();
	}

	private static String cuotaDe(String detalleCobro, String deudor) {
		List<String> ids = JsonPath.read(detalleCobro, "$.cuotas[?(@.deudorCuentaId == '" + deudor + "')].id");
		return ids.get(0);
	}

	private static String cuerpo(MvcTestResult resultado) {
		try {
			return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

}
