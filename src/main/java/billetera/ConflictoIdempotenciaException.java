package billetera;

public class ConflictoIdempotenciaException extends BilleteraException {

	public ConflictoIdempotenciaException(String llave) {
		super("La llave de idempotencia '" + llave + "' ya se usó con otros datos");
	}

}
