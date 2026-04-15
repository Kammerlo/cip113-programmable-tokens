package org.cardanofoundation.cip113.service.substandard.capabilities;

import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;

/**
 * Global state management capability for substandards that use a global state UTxO.
 * Supports operations like pausing transfers, updating mintable amount, and
 * modifying security/compliance information.
 */
public interface GlobalStateManageable {

    /**
     * Request to update the global state UTxO.
     * Exactly one of the action-specific fields should be non-null.
     */
    record GlobalStateUpdateRequest(
            /** Admin address performing the update */
            String adminAddress,
            /** Policy ID of the programmable token */
            String policyId,
            /** Action to perform */
            GlobalStateAction action,
            /** New value for transfers_paused (for PAUSE_TRANSFERS) */
            Boolean transfersPaused,
            /** New value for mintable_amount (for UPDATE_MINTABLE_AMOUNT) */
            Long mintableAmount,
            /** New security info as hex-encoded bytes (for MODIFY_SECURITY_INFO) */
            String securityInfo
    ) {}

    enum GlobalStateAction {
        PAUSE_TRANSFERS,
        UPDATE_MINTABLE_AMOUNT,
        MODIFY_SECURITY_INFO
    }

    /**
     * Build a transaction to update the global state UTxO.
     *
     * @param request        The update request specifying which field to change
     * @param protocolParams The protocol bootstrap parameters
     * @return Transaction context with unsigned CBOR tx
     */
    TransactionContext<Void> buildGlobalStateUpdateTransaction(
            GlobalStateUpdateRequest request,
            ProtocolBootstrapParams protocolParams);
}
