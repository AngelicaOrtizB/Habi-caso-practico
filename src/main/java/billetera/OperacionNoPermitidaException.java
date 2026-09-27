package billetera;

/**
 * El pedido está bien escrito, pero el estado actual no lo permite. Por ejemplo:
 * pagar una cuota que ya se pagó, o pagar una cuota de un cobro cancelado.
 */
public class OperacionNoPermitidaException extends BilleteraException {

	public OperacionNoPermitidaException(String mensaje) {
		super(mensaje);
	}

}
