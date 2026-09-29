package com.catalogue.verg.user.repository;

import com.catalogue.verg.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    /** Login lookup by email; a list, oldest first, because emails are not unique yet. */
    @Query(value = "SELECT u.* FROM \"user\" u WHERE u.data ->> 'email' = :email ORDER BY u.created_on ASC",
            nativeQuery = true)
    List<UserEntity> findByEmail(@Param("email") String email);
}
