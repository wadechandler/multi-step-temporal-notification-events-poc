package com.wadechandler.notification.poc.repository;

import com.wadechandler.notification.poc.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
}
