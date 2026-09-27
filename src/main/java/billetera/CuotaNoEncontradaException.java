package billetera;

import java.util.UUID;

public class CuotaNoEncontradaException extends BilleteraException {

	public CuotaNoEncontradaException(UUID cuotaId) {
		super("La cuota " + cuotaId + " no existe");
	}

}
