package org.vaadin.marcus.v1;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
import static org.vaadin.marcus.v1.Domain.*;

interface Users extends JpaRepository<User,Long> { Optional<User> findByUsername(String username); }
interface Orders extends JpaRepository<Order,String> {
    List<Order> findByUserIdOrderById(Long userId);
    Optional<Order> findByIdAndUserId(String id,Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from FlightOrder o where o.id=:id and o.userId=:owner")
    Optional<Order> lockOwned(@Param("id") String id,@Param("owner") Long owner);
}
interface Documents extends JpaRepository<Document,UUID> {
    @Query("select d from KnowledgeDocument d where d.ownerId=:owner or d.ownerId is null order by d.createdAt desc") List<Document> visible(@Param("owner") Long owner);
    Optional<Document> findByTitleAndVersionAndOwnerIdIsNull(String title,String version);
}
interface Plans extends JpaRepository<Plan,UUID> {
    List<Plan> findByUserIdOrderByCreatedAtDesc(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from OperationPlan p where p.id=:id and p.userId=:owner")
    Optional<Plan> lockOwned(@Param("id") UUID id,@Param("owner") Long owner);
}
interface Operations extends JpaRepository<Operation,UUID> { List<Operation> findTop100ByUserIdOrderByCreatedAtDesc(Long owner); }
interface Conversations extends JpaRepository<Conversation,UUID> {
    Optional<Conversation> findByIdAndUserId(UUID id,Long owner);
    List<Conversation> findByUserIdOrderByCreatedAtDesc(Long owner);
}
interface Messages extends JpaRepository<Message,Long> { List<Message> findTop100ByConversationIdOrderByIdDesc(UUID id); }
