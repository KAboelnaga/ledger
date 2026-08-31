package com.kareem.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "journal_entry")
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "external_reference", nullable = false, unique = true, updatable = false, length = 64)
    private String externalReference;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry(){}

    public JournalEntry(Instant occurredAt,String description, String externalReference){
        this.occurredAt = occurredAt;
        this.description = description;
        this.externalReference = externalReference;
    }

    public JournalEntry(Instant occurredAt, String externalReference){
        this(occurredAt, null, externalReference);
    }

    public Long getId(){
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDescription() {
        return description;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setDescription(String description){
        this.description = description;
    }

    public void addPosting(Posting posting){
        postings.add(posting);
    }

    public List<Posting> getPostings(){
        return Collections.unmodifiableList(postings);
    }


}
