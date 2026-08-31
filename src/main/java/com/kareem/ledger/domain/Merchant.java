package com.kareem.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Merchant(){
    }
    public Merchant(String name, MerchantStatus status){
        this.name = name;
        this.status = status;
    }

    public Long getId(){
        return id;
    }

    public String getName(){
        return name;
    }

    public void setName(String name){
        this.name = name;
    }

    public MerchantStatus getStatus(){
        return status;
    }

    public void setStatus(MerchantStatus status ){
        this.status = status;
    }

    public Instant getCreatedAt(){
        return createdAt;
    }

}
