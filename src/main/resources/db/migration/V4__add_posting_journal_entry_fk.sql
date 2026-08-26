ALTER TABLE posting
    ADD CONSTRAINT posting_journal_entry_fk
    FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id)
    ON DELETE RESTRICT;