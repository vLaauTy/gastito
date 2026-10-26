-- Juntadas: lo que ya pagó cada uno. Si paga menos de lo que debe y queda debiendo, sigue pendiente por la
-- diferencia (monto - monto_pagado); si se da por saldado, queda pagado aunque haya pagado menos.
ALTER TABLE juntada_participante ADD COLUMN monto_pagado NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE juntada_participante ADD CONSTRAINT ck_participante_monto_pagado CHECK (monto_pagado >= 0);
