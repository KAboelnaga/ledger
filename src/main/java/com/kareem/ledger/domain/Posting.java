package com.kareem.ledger.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Posting(){

    }

    public Posting(JournalEntry journalEntry, Account account, long amount, String currency){
        this.journalEntry = journalEntry;
        this.account = account;
        this.amount = amount;
        this.currency = currency;
    }

    public Long getId() {
        return id;
    }

    public JournalEntry getJournalEntry() {
        return journalEntry;
    }

    public Account getAccount() {
        return account;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
