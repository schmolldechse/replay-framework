package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;

/** Creates the real adapter capture index for packet-definition context tests. */
public final class Paper26_2CaptureTestState {

    private Paper26_2CaptureTestState() {
    }

    public static PacketCaptureState create() {
        return new dev.voldechse.replayframework.adapter.paper.capture.CaptureStateIndex();
    }
}
