package billetera;

import java.util.UUID;

public class CuentaNoEncontradaException extends BilleteraException {

	public CuentaNoEncontradaException(UUID cuentaId) {
		super("La cuenta " + cuentaId + " no existe");
	}

}
