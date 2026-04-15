package org.cardanofoundation.cip113.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Registration request for the "kyc" substandard.
 * Includes the Trusted Entity List (TEL) policy ID that will be used
 * to verify KYC attestations during token transfers.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KycRegisterRequest extends RegisterTokenRequest {

    /**
     * Public key hash of the admin who will manage this token (issuer).
     * Used to parameterize the issuer admin contract.
     */
    private String adminPubKeyHash;

    /**
     * Policy ID of the Trusted Entity List (TEL) linked list node NFTs.
     * The transfer validator will check KYC attestations against entities in this list.
     */
    private String telPolicyId;
}
