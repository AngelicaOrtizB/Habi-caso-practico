-- V1: esquema inicial de la billetera.
-- Todos los montos van en centavos como BIGINT: nunca decimales flotantes.

-- ============================================================
-- CUENTAS
-- ============================================================
-- Las cuentas SISTEMA representan plata que entra o sale del sistema
-- (ej. el fondeo externo simulado). Son las únicas que pueden quedar negativas.
CREATE TABLE cuentas (
    id             UUID PRIMARY KEY,
    titular        VARCHAR(120) NOT NULL,
    tipo           VARCHAR(10)  NOT NULL DEFAULT 'USUARIO'
                   CHECK (tipo IN ('USUARIO', 'SISTEMA')),
    -- Caché del saldo: siempre igual a la suma de los movimientos de la cuenta.
    -- Se actualiza en la misma transacción de BD en que se escribe el movimiento.
    saldo_centavos BIGINT       NOT NULL DEFAULT 0,
    creada_en      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Segunda línea de defensa: aunque el código falle, un usuario
    -- nunca puede quedar con saldo negativo.
    CONSTRAINT chk_saldo_no_negativo
        CHECK (tipo = 'SISTEMA' OR saldo_centavos >= 0)
);

-- ============================================================
-- TRANSACCIONES (el "qué pasó")
-- ============================================================
CREATE TABLE transacciones (
    id                 UUID PRIMARY KEY,
    tipo               VARCHAR(20)  NOT NULL
                       CHECK (tipo IN ('CARGA', 'TRANSFERENCIA')),
    -- La llave la manda el cliente. UNIQUE evita mover plata dos veces
    -- cuando el cliente reintenta, incluso con reintentos concurrentes.
    llave_idempotencia VARCHAR(100) NOT NULL UNIQUE,
    descripcion        VARCHAR(255),
    creada_en          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- MOVIMIENTOS (el "cómo se movió la plata") - inmutables
-- ============================================================
-- Cada transacción genera al menos 2 movimientos cuya suma es exactamente 0.
-- Negativo = sale de la cuenta, positivo = entra.
CREATE TABLE movimientos (
    id             BIGSERIAL PRIMARY KEY,
    transaccion_id UUID        NOT NULL REFERENCES transacciones(id),
    cuenta_id      UUID        NOT NULL REFERENCES cuentas(id),
    monto_centavos BIGINT      NOT NULL CHECK (monto_centavos <> 0),
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Historial de una cuenta paginado por id (cursor): el id es estrictamente
-- creciente, a diferencia de creado_en, que se repite dentro de una transacción.
CREATE INDEX idx_movimientos_cuenta ON movimientos(cuenta_id, id DESC);
CREATE INDEX idx_movimientos_transaccion ON movimientos(transaccion_id);

-- Garantía a nivel de base de datos: al hacer COMMIT, cada transacción
-- debe sumar exactamente cero. Si no, Postgres rechaza todo.
-- Es DEFERRED porque los movimientos se insertan de a uno: después del
-- primero la suma todavía no es cero, y eso es válido hasta el COMMIT.
CREATE FUNCTION verificar_transaccion_cuadrada() RETURNS TRIGGER AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(monto_centavos), 0) INTO total
    FROM movimientos
    WHERE transaccion_id = NEW.transaccion_id;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Transacción % descuadrada: suma = %',
            NEW.transaccion_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_transaccion_cuadrada
    AFTER INSERT ON movimientos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verificar_transaccion_cuadrada();

-- Los movimientos no se editan ni se borran: los errores se corrigen con
-- un movimiento en sentido contrario, como en un extracto bancario.
CREATE FUNCTION prohibir_cambios_en_movimientos() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'La tabla movimientos es inmutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_movimientos_inmutables
    BEFORE UPDATE OR DELETE ON movimientos
    FOR EACH ROW EXECUTE FUNCTION prohibir_cambios_en_movimientos();

-- ============================================================
-- DATOS INICIALES
-- ============================================================
-- Cuenta SISTEMA contrapartida de toda carga de saldo simulada: la plata
-- "entra" desde aquí, que queda negativa, y la suma total sigue siendo 0.
INSERT INTO cuentas (id, titular, tipo)
VALUES ('00000000-0000-0000-0000-000000000001', 'Fondeo externo (simulado)', 'SISTEMA');
