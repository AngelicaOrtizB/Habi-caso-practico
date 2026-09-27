package billetera;

/** Error de negocio: la operación se rechazó y no movió plata. */
public abstract class BilleteraException extends RuntimeException {

	protected BilleteraException(String mensaje) {
		super(mensaje);
	}

}
