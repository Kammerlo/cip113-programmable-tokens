package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.TelInitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for KYC TEL (Trusted Entity List) global state initialization data.
 */
@Repository
public interface TelInitRepository extends JpaRepository<TelInitEntity, String> {

    /**
     * Find TEL init by global state policy ID.
     */
    Optional<TelInitEntity> findByTelNodePolicyId(String telNodePolicyId);

    /**
     * Find all TEL inits by admin public key hash.
     */
    List<TelInitEntity> findByAdminPkh(String adminPkh);
}
