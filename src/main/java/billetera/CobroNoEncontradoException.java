package billetera;

import java.util.UUID;

public class CobroNoEncontradoException extends BilleteraException {

	public CobroNoEncontradoException(UUID cobroId) {
		super("El cobro " + cobroId + " no existe");
	}

}
