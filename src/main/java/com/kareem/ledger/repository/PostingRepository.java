package com.kareem.ledger.repository;

import com.kareem.ledger.domain.Posting;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PostingRepository extends Repository<Posting, Long> {

    Posting save(Posting posting);

    Optional<Posting> findById(Long id);

    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.account.id = :accountId")
    long balanceByAccountId(@Param("accountId") Long accountId);

}
