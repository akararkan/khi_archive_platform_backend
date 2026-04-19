package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.model.User;

public interface PasswordResetDeliveryService {

    void deliver(User user, String resetToken);
}

