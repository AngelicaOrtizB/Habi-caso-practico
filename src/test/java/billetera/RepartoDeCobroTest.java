package billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** No usa base de datos: el reparto es una cuenta pura. */
class RepartoDeCobroTest {

	@Test
	void cienMilPesosEntreTresNoPierdeElCentavo() {
		assertThat(RepartoDeCobro.repartir(10_000_000, 3)).containsExactly(3_333_334L, 3_333_333L, 3_333_333L);
	}

	@Test
	void siSobranVariosCentavosSeLosLlevanLosPrimerosDeAUno() {
		assertThat(RepartoDeCobro.repartir(11, 4)).containsExactly(3L, 3L, 3L, 2L);
	}

	@Test
	void siEsExactoTodosPaganLoMismo() {
		assertThat(RepartoDeCobro.repartir(9_000, 3)).containsExactly(3_000L, 3_000L, 3_000L);
	}

	@Test
	void unCentavoParaUnaPersona() {
		assertThat(RepartoDeCobro.repartir(1, 1)).containsExactly(1L);
	}

	@Test
	void siNoAlcanzaUnCentavoPorPersonaSeRechaza() {
		assertThatThrownBy(() -> RepartoDeCobro.repartir(2, 3)).isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void sinPersonasSeRechaza() {
		assertThatThrownBy(() -> RepartoDeCobro.repartir(100, 0)).isInstanceOf(OperacionInvalidaException.class);
	}

	/**
	 * Prueba 10.000 repartos al azar. En todos: la suma es exactamente el total, nadie
	 * paga más de 1 centavo que otro, y los que pagan más son los primeros.
	 */
	@Test
	void milesDeRepartosAlAzarSiempreSumanElTotal() {
		Random azar = new Random(2027);
		for (int i = 0; i < 10_000; i++) {
			int personas = 1 + azar.nextInt(50);
			long total = personas + azar.nextLong(1_000_000_000L);

			List<Long> cuotas = RepartoDeCobro.repartir(total, personas);

			assertThat(cuotas).hasSize(personas);
			assertThat(cuotas.stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
			assertThat(Collections.max(cuotas) - Collections.min(cuotas)).isLessThanOrEqualTo(1);
			assertThat(cuotas).isSortedAccordingTo(Collections.reverseOrder());
		}
	}

}
