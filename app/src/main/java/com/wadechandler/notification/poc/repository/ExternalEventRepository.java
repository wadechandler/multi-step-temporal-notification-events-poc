package com.wadechandler.notification.poc.repository;

import com.wadechandler.notification.poc.model.ExternalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExternalEventRepository extends JpaRepository<ExternalEvent, UUID> {
}
