package ak.dev.khi_archive_platform.platform.exceptions;

public class CorrectionAlreadyProcessedException extends RuntimeException {
    public CorrectionAlreadyProcessedException(String message) {
        super(message);
    }
}
