package com.wadechandler.notification.poc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalEvent {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean processed = false;
}
