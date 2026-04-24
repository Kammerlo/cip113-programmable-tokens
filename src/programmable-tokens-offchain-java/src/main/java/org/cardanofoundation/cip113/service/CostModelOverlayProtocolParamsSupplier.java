package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

@Slf4j
@RequiredArgsConstructor
public class CostModelOverlayProtocolParamsSupplier implements ProtocolParamsSupplier {

    private static final String PLUTUS_V3 = "PlutusV3";

    private final ProtocolParamsSupplier primary;
    private final ProtocolParamsSupplier costModelSource;

    @Override
    public ProtocolParams getProtocolParams() {
        ProtocolParams params = primary.getProtocolParams();

        LinkedHashMap<String, Long> overlay;
        try {
            ProtocolParams source = costModelSource.getProtocolParams();
            overlay = source.getCostModels() == null ? null : source.getCostModels().get(PLUTUS_V3);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch PlutusV3 cost model from overlay source; using primary params as-is", e);
            return params;
        }

        if (overlay == null || overlay.isEmpty()) {
            log.warn("Overlay source returned no PlutusV3 cost model; using primary params as-is");
            return params;
        }

        LinkedHashMap<String, LinkedHashMap<String, Long>> merged =
                params.getCostModels() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params.getCostModels());
        LinkedHashMap<String, Long> previous = merged.put(PLUTUS_V3, overlay);
        int prevSize = previous == null ? 0 : previous.size();
        log.debug("Overlayed PlutusV3 cost model: primary had {} entries, overlay has {}", prevSize, overlay.size());
        params.setCostModels(merged);
        return params;
    }
}
