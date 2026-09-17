package com.notifly.notification.channel;

import com.notifly.notification.channel.dto.ChannelConfigResponse;
import com.notifly.notification.channel.dto.UpdateChannelConfigRequest;
import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A tenant's own channel configuration.
 *
 * <p>Every path operates on the caller's tenant, taken from their token. No tenant id appears in
 * these URLs, so there is no way to express "configure someone else's channel" in the first
 * place.
 */
@RestController
@RequestMapping("/api/v1/channels")
@Tag(name = "Channel configuration", description = "Per-channel provider settings for the caller's tenant. Requires TENANT_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a tenant administrator", content = @Content)
})
public class ChannelConfigController {

    private final ChannelConfigService channelConfigService;

    public ChannelConfigController(ChannelConfigService channelConfigService) {
        this.channelConfigService = channelConfigService;
    }

    @GetMapping
    @Operation(summary = "List every channel and its configuration",
            description = "Channels that have never been configured are returned as explicit placeholders rather than omitted.")
    public ResponseEntity<List<ChannelConfigResponse>> list() {
        return ResponseEntity.ok(channelConfigService.listAll());
    }

    @GetMapping("/{channel}")
    @Operation(summary = "Fetch one channel's configuration",
            description = "Credentials are never returned; `credentialsHint` carries a masked tail instead.")
    public ResponseEntity<ChannelConfigResponse> get(@PathVariable Channel channel) {
        return ResponseEntity.ok(channelConfigService.get(channel));
    }

    @PutMapping("/{channel}")
    @Operation(summary = "Create or update a channel's configuration",
            description = """
                    Upsert; omitted fields are left unchanged. Supplying `credentials` replaces
                    the stored value, which is encrypted with AES-256-GCM before it is written and
                    is never readable back through the API.
                    """)
    public ResponseEntity<ChannelConfigResponse> upsert(@PathVariable Channel channel,
                                                        @Valid @RequestBody UpdateChannelConfigRequest request) {
        return ResponseEntity.ok(channelConfigService.upsert(channel, request));
    }

    @PostMapping("/{channel}/enable")
    @Operation(summary = "Enable a configured channel")
    @ApiResponses(@ApiResponse(responseCode = "400", description = "Channel has never been configured", content = @Content))
    public ResponseEntity<ChannelConfigResponse> enable(@PathVariable Channel channel) {
        return ResponseEntity.ok(channelConfigService.setEnabled(channel, true));
    }

    @PostMapping("/{channel}/disable")
    @Operation(summary = "Disable a channel",
            description = "Submissions to a disabled channel are rejected at validation, before any work is queued.")
    public ResponseEntity<ChannelConfigResponse> disable(@PathVariable Channel channel) {
        return ResponseEntity.ok(channelConfigService.setEnabled(channel, false));
    }
}
