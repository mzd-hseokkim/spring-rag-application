package kr.co.mz.ragservice.config;

import kr.co.mz.ragservice.auth.AppUserRepository;
import kr.co.mz.ragservice.auth.JwtTokenProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final AppUserRepository appUserRepository;

    public WebSocketAuthInterceptor(JwtTokenProvider jwtTokenProvider,
                                     AppUserRepository appUserRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.appUserRepository = appUserRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    UUID userId = jwtTokenProvider.getUserIdFromToken(token);
                    String role = appUserRepository.findById(userId)
                            .map(u -> u.getRole().name())
                            .orElse("USER");
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                    var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
                    accessor.setUser(auth);
                    // 수정된 헤더로 새 메시지를 반환해야 Principal이 세션에 저장됨
                    return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
                }
            }
        }
        return message;
    }
}
