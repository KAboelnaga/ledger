package com.kareem.ledger.repository;

import com.kareem.ledger.domain.JournalEntry;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface JournalEntryRepository extends Repository<JournalEntry, Long> {

    JournalEntry save(JournalEntry journalEntry);

    Optional<JournalEntry> findById(Long id);
}
