package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LoggingPasswordResetDeliveryService implements PasswordResetDeliveryService {

    @Override
    public void deliver(User user, String resetToken) {
        log.info("Password reset token prepared for userId={} email={}. Configure a real delivery provider to send it securely.",
                user.getUserId(), user.getEmail());
        log.debug("Password reset token for userId={}: {}", user.getUserId(), resetToken);
    }
}

