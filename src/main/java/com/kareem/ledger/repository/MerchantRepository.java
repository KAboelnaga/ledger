package com.kareem.ledger.repository;

import com.kareem.ledger.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
}
