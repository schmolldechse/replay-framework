package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;

/** Creates the real adapter capture index for packet-definition context tests. */
public final class Paper26_3CaptureTestState {

    private Paper26_3CaptureTestState() {
    }

    public static PacketCaptureState create() {
        return new dev.voldechse.replayframework.adapter.paper.capture.CaptureStateIndex();
    }
}
