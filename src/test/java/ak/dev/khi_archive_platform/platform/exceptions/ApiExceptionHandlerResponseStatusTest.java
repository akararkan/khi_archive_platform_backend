package ak.dev.khi_archive_platform.platform.exceptions;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerResponseStatusTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private MockHttpServletRequest coverRequest() {
        return new MockHttpServletRequest("GET", "/api/guest/text/BOOK_TXT_000001/cover");
    }

    @Test
    void notFoundKeepsItsStatusAndReason() {
        ResponseEntity<ApiErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Cover image not available"),
                coverRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("Cover image not available");
    }

    @Test
    void otherClientErrorsKeepTheirStatus() {
        ResponseEntity<ApiErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Bad range"),
                coverRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void serverErrorsKeepFiveHundredWithTheGivenMessage() {
        ResponseEntity<ApiErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serve cover image"),
                coverRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("Failed to serve cover image");
    }

    @Test
    void missingReasonFallsBackToStatusPhrase() {
        ResponseEntity<ApiErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND),
                coverRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Not Found");
    }
}
