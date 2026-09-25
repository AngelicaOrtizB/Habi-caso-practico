-- V1: esquema inicial
-- Montos siempre en centavos (BIGINT). Nunca decimales flotantes.

-- ============================================================
-- CUENTAS
-- ============================================================
-- Las cuentas SYSTEM (ej. "fondeo externo") representan dinero que
-- entra/sale del sistema y son las únicas que pueden quedar negativas.
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    owner_name    VARCHAR(120) NOT NULL,
    kind          VARCHAR(10)  NOT NULL DEFAULT 'USER'
                  CHECK (kind IN ('USER', 'SYSTEM')),
    balance_cents BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Segunda línea de defensa: aunque el código falle, un usuario
    -- nunca puede quedar con saldo negativo.
    CONSTRAINT chk_balance_non_negative
        CHECK (kind = 'SYSTEM' OR balance_cents >= 0)
);

-- ============================================================
-- TRANSACCIONES (el "qué pasó")
-- ============================================================
CREATE TABLE transactions (
    id              UUID PRIMARY KEY,
    type            VARCHAR(20)  NOT NULL
                    CHECK (type IN ('DEPOSIT', 'TRANSFER', 'SPLIT_PAYMENT')),
    -- Evita cobros dobles cuando el cliente reintenta.
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- LEDGER (el "cómo se movió la plata") - inmutable
-- ============================================================
-- Cada transacción genera >= 2 asientos cuya suma es exactamente 0.
-- Negativo = sale de la cuenta, positivo = entra.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID   NOT NULL REFERENCES transactions(id),
    account_id     UUID   NOT NULL REFERENCES accounts(id),
    amount_cents   BIGINT NOT NULL CHECK (amount_cents <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_account ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_ledger_tx ON ledger_entries(transaction_id);

-- Garantía a nivel de base de datos: al hacer COMMIT, cada transacción
-- debe sumar exactamente cero. Si no, Postgres rechaza todo.
CREATE FUNCTION check_transaction_balanced() RETURNS TRIGGER AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_cents), 0) INTO total
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Transacción % descuadrada: suma = %',
            NEW.transaction_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_transaction_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

-- El ledger no se edita ni se borra: los errores se corrigen con
-- asientos compensatorios, igual que en contabilidad real.
CREATE FUNCTION forbid_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries es inmutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- ============================================================
-- COBROS DIVIDIDOS (feature extra)
-- ============================================================
CREATE TABLE split_requests (
    id                 UUID PRIMARY KEY,
    creator_account_id UUID         NOT NULL REFERENCES accounts(id),
    total_cents        BIGINT       NOT NULL CHECK (total_cents > 0),
    description        VARCHAR(255) NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE split_shares (
    id                  UUID PRIMARY KEY,
    split_request_id    UUID        NOT NULL REFERENCES split_requests(id),
    debtor_account_id   UUID        NOT NULL REFERENCES accounts(id),
    amount_cents        BIGINT      NOT NULL CHECK (amount_cents > 0),
    status              VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    -- Una cuota pagada apunta exactamente a UNA transacción; UNIQUE
    -- impide que la misma transacción "pague" dos cuotas.
    paid_transaction_id UUID UNIQUE REFERENCES transactions(id),
    paid_at             TIMESTAMPTZ,
    -- Una persona tiene una sola cuota por cobro.
    CONSTRAINT uq_one_share_per_debtor UNIQUE (split_request_id, debtor_account_id),
    -- Coherencia de estado: PAID si y solo si hay transacción asociada.
    CONSTRAINT chk_paid_consistency CHECK (
        (status = 'PAID' AND paid_transaction_id IS NOT NULL AND paid_at IS NOT NULL)
        OR
        (status <> 'PAID' AND paid_transaction_id IS NULL AND paid_at IS NULL)
    )
);

CREATE INDEX idx_shares_debtor ON split_shares(debtor_account_id, status);

-- Cuenta de sistema para simular cargas de saldo (dinero que entra desde fuera).
INSERT INTO accounts (id, owner_name, kind)
VALUES ('00000000-0000-0000-0000-000000000001', 'Fondeo externo (simulado)', 'SYSTEM');
