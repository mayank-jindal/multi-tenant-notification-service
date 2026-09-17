package com.notifly.notification.template;

import com.notifly.notification.common.model.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateChannelBodyRepository extends JpaRepository<TemplateChannelBody, UUID> {

    List<TemplateChannelBody> findAllByTemplateVersionId(UUID templateVersionId);

    Optional<TemplateChannelBody> findByTemplateVersionIdAndChannel(UUID templateVersionId, Channel channel);

    void deleteAllByTemplateVersionId(UUID templateVersionId);
}
