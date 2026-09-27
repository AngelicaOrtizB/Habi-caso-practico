package billetera;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide un total entre N personas sin perder ni inventar un centavo.
 * <p>
 * Ejemplo: $100.000 entre 3 = 10.000.000 centavos / 3 = 3.333.333 y sobra 1 centavo.
 * Ese centavo se lo lleva la primera persona: $33.333,34 + $33.333,33 + $33.333,33.
 * Si sobran 2, se llevan uno cada una de las dos primeras, y así. Nunca sobran N o más,
 * porque el residuo de dividir entre N siempre es menor que N.
 */
public final class RepartoDeCobro {

	private RepartoDeCobro() {
	}

	public static List<Long> repartir(long totalCentavos, int personas) {
		if (personas < 1) {
			throw new OperacionInvalidaException("Debe haber al menos una persona para repartir el cobro");
		}
		if (totalCentavos < personas) {
			throw new OperacionInvalidaException("El total no alcanza para darle al menos 1 centavo a cada persona");
		}
		long base = totalCentavos / personas;
		long sobrante = totalCentavos % personas;
		List<Long> cuotas = new ArrayList<>(personas);
		for (int i = 0; i < personas; i++) {
			cuotas.add(i < sobrante ? base + 1 : base);
		}
		return cuotas;
	}

}
