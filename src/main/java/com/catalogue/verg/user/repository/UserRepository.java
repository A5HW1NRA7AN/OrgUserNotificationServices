package com.catalogue.verg.user.repository;

import com.catalogue.verg.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    /**
     * Resolves a login by email against postgres, the only store that holds the credential.
     *
     * <p>Returns a list, not an Optional: nothing enforces email uniqueness, and an Optional would
     * throw on a duplicate, turning a data-quality problem into a 500 on login. Ordered by
     * createdOn so the winner is stable rather than decided by row order.
     */
    @Query(value = "SELECT u.* FROM \"user\" u WHERE u.data ->> 'email' = :email ORDER BY u.created_on ASC",
            nativeQuery = true)
    List<UserEntity> findByEmail(@Param("email") String email);
}
