package billetera;

import java.util.UUID;

public class FondosInsuficientesException extends BilleteraException {

	public FondosInsuficientesException(UUID cuentaId) {
		super("La cuenta " + cuentaId + " no tiene saldo suficiente");
	}

}
