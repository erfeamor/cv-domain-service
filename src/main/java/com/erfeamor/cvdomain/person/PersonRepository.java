package com.erfeamor.cvdomain.person;

import com.erfeamor.cvdomain.common.VersionBumping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long>,
        VersionBumping<Person> {
}
