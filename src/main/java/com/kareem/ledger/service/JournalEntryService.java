package com.kareem.ledger.service;

import com.kareem.ledger.domain.Account;
import com.kareem.ledger.domain.JournalEntry;
import com.kareem.ledger.repository.AccountRepository;
import com.kareem.ledger.repository.JournalEntryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class JournalEntryService {
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public JournalEntryService(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository){
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }
    @Transactional
    public Long post(Instant occurredAt, String description, String externalReference, List<PostingLine> postings){

        if(postings.size() < 2){
            throw new IllegalArgumentException("Insufficient Postings for a journal entry, only " + postings.size());
        }

        List<Account> accounts = new ArrayList<>();
        long total = 0;

        for(PostingLine line : postings){
            Account account = accountRepository.findById(line.accountId()).orElseThrow(() -> new IllegalArgumentException("no account " + line.accountId()));
            accounts.add(account);

            String accountCurrency = account.getCurrency();

            if (!accountCurrency.equals(line.currency())){
                throw new IllegalArgumentException("Currencies don't match, the currency of account is " + accountCurrency + " while the posting currency is " + line.currency());
            }
            total += line.amount();
        }

        if (total != 0){
            throw new IllegalArgumentException("postings must sum to zero, got " + total);
        }

        JournalEntry journalEntry = new JournalEntry(occurredAt, description, externalReference);

        for(int i = 0; i < postings.size() ; i ++){

            PostingLine line = postings.get(i);
            journalEntry.addPosting(accounts.get(i), line.amount(), line.currency());
        }

        journalEntryRepository.save(journalEntry);
        return journalEntry.getId();
    }
}

