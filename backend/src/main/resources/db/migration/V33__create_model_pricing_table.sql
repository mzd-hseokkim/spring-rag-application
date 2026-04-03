CREATE TABLE model_pricing (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_name VARCHAR(200) NOT NULL UNIQUE,
    input_price_per_1m NUMERIC(10, 4) NOT NULL DEFAULT 0,
    output_price_per_1m NUMERIC(10, 4) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'USD',
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
