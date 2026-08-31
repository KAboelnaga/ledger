package com.kareem.ledger.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private AccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    protected Account(){}

    public Account(String code, String name, AccountType type, String currency, Merchant merchant){
        this.code = code;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.merchant = merchant;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCode(){
        return code;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Merchant getMerchant() {
        return merchant;
    }

}
