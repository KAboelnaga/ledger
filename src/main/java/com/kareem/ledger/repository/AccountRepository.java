package com.kareem.ledger.repository;


import com.kareem.ledger.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
