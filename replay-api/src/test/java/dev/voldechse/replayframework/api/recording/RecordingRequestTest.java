package dev.voldechse.replayframework.api.recording;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingRequestTest {

    @Test
    void preservesSelectedParticipantsAsAnImmutableRecordingFilter() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");

        RecordingRequest request = RecordingRequest.builder()
                .title("participant-filter")
                .description("test")
                .scope(RecordingScope.builder().build())
                .capturePolicy(CapturePolicy.builder().build())
                .budget(ReplayBudget.builder().maxSegmentBytes(1).build())
                .participants(List.of(first, second))
                .build();

        assertEquals(Set.of(first, second), request.participants());
        assertThrows(UnsupportedOperationException.class, () -> request.participants().clear());
    }
}
