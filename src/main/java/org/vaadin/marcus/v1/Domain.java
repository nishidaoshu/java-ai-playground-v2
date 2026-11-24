package org.vaadin.marcus.v1;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public final class Domain {
    @Entity @Table(name="app_users")
    public static class User {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
        public String username;
        @JsonIgnore @Column(name="password_hash") public String passwordHash;
        public String displayName;
        public String role;
    }
    @Entity(name="FlightOrder") @Table(name="flight_orders")
    public static class Order {
        @Id public String id;
        public Long userId;
        public LocalDate flightDate;
        public String origin;
        public String destination;
        public String cabin;
        public String status;
        public BigDecimal fare;
        @Version public long version;
    }
    @Entity(name="KnowledgeDocument") @Table(name="knowledge_documents")
    public static class Document {
        @Id public UUID id;
        public Long ownerId;
        public String title;
        public String version;
        public String filename;
        @JsonIgnore @Column(columnDefinition="text") public String content;
        public String sha256;
        public boolean authoritative;
        public Instant createdAt;
    }
    @Entity(name="OperationPlan") @Table(name="operation_plans")
    public static class Plan {
        @Id public UUID id;
        public Long userId;
        public String orderId;
        public long orderVersion;
        public String action;
        public LocalDate newDate;
        public String newCabin;
        public BigDecimal fee;
        public BigDecimal refund;
        public Instant expiresAt;
        public Instant createdAt;
        public String status;
        public String ruleVersion;
        @Column(columnDefinition="text") public String evidence;
        public Instant executedAt;
        public Long resultVersion;
    }
    @Entity @Table(name="operation_records")
    public static class Operation {
        @Id public UUID id;
        public Long userId;
        public UUID planId;
        public String orderId;
        public String action;
        public String outcome;
        @Column(columnDefinition="text") public String reason;
        public UUID requestId;
        public Instant createdAt;
    }
    @Entity @Table(name="conversations")
    public static class Conversation {
        @Id public UUID id;
        public Long userId;
        public Instant createdAt;
    }
    @Entity @Table(name="chat_messages")
    public static class Message {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
        public UUID conversationId;
        public String role;
        @Column(columnDefinition="text") public String content;
        @Column(columnDefinition="text") public String metadata;
        public UUID requestId;
        public Instant createdAt;
    }
}
