/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.webrtc;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.signaling.CloseCode;
import org.saltyrtc.demo.app.BuildConfig;
import org.saltyrtc.demo.app.Config;
import org.saltyrtc.demo.app.MainActivity;
import org.saltyrtc.demo.app.StateType;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.events.MessageHandler;
import org.saltyrtc.tasks.webrtc.exceptions.UntiedException;
import org.saltyrtc.tasks.webrtc.messages.Answer;
import org.saltyrtc.tasks.webrtc.messages.Candidate;
import org.saltyrtc.tasks.webrtc.messages.Offer;
import org.saltyrtc.tasks.webrtc.transport.SignalingTransportHandler;
import org.saltyrtc.tasks.webrtc.transport.SignalingTransportLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@AnyThread
public class PeerConnectionHelper {
    @NonNull private static final String DC_LABEL = "much-secure";

    @NonNull private final Logger log = LoggerFactory.getLogger("SaltyRTC.Demo.PC");
    @NonNull private final WebRTCTask task;
    @NonNull private final MainActivity activity;
    @NonNull private final PeerConnectionFactory factory;
    @NonNull private final MediaConstraints constraints;
    @NonNull private final PeerConnection pc;
    private boolean dcOpened = false;

    public PeerConnectionHelper(@NonNull final WebRTCTask task, @NonNull final MainActivity activity) {
        this.task = task;
        this.activity = activity;

        // Initialize factory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(activity)
                .setEnableInternalTracer(BuildConfig.DEBUG)
                .createInitializationOptions());
        this.factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory();

        // Set media constraints
        this.constraints = new MediaConstraints();

        // Set ICE servers
        final List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(
            PeerConnection.IceServer.builder("stun:" + Config.STUN_SERVER).createIceServer());
        if (Config.TURN_SERVER != null) {
            iceServers.add(
                PeerConnection.IceServer.builder("turn:" + Config.TURN_SERVER)
                    .setUsername(Config.TURN_USER)
                    .setPassword(Config.TURN_PASS)
                    .createIceServer());
        }

        // Create peer connection & bind events
        this.pc = Objects.requireNonNull(this.factory.createPeerConnection(iceServers, new PeerConnectionObserver()));

        // Bind task events
        this.task.setMessageHandler(new TaskMessageHandler());

        // Create data channel for handover and initiate handover once open
        this.prepareHandover();
    }

    @AnyThread
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(@NonNull final PeerConnection.SignalingState signalingState) {
            log.debug("Signaling state change: " + signalingState.name());
            PeerConnectionHelper.this.activity.setState(StateType.RTC_SIGNALING, signalingState.name());
        }

        @Override
        public void onIceConnectionChange(@NonNull final PeerConnection.IceConnectionState iceConnectionState) {
            log.debug("ICE connection change to " + iceConnectionState.name());
            PeerConnectionHelper.this.activity.setState(StateType.RTC_ICE_CONNECTION, iceConnectionState.name());
        }

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {
            log.debug("ICE connection receiving change: " + receiving);
        }

        @Override
        public void onIceGatheringChange(@NonNull final PeerConnection.IceGatheringState iceGatheringState) {
            log.debug("ICE gathering change: " + iceGatheringState.name());
            PeerConnectionHelper.this.activity.setState(StateType.RTC_ICE_GATHERING, iceGatheringState.name());
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            log.debug("ICE candidate gathered: " + iceCandidate.sdp);

            // Send candidate to the remote peer
            final Candidate candidate = new Candidate(iceCandidate.sdp, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex);
            try {
                PeerConnectionHelper.this.task.sendCandidates(new Candidate[] { candidate });
            } catch (final ConnectionException error) {
                log.error("Could not send ICE candidate", error);
            }
        }

        @Override
        public void onIceCandidatesRemoved(@NonNull final IceCandidate[] iceCandidates) {
            log.debug("ICE candidates removed");
        }

        @Override
        public void onDataChannel(@NonNull final DataChannel dc) {
            log.debug("New data channel was created: " + dc.label());
            if (!DC_LABEL.equals(dc.label())) {
                return;
            }

            // Notify main class about this new data channel.
            PeerConnectionHelper.this.activity.onDataChannel(dc);
        }

        @Override
        public void onRenegotiationNeeded() {
            log.debug("Negotiation needed");
        }

        @Override
        public void onAddStream(@NonNull final MediaStream mediaStream) {
            log.warn("Stream added");
        }

        @Override
        public void onRemoveStream(@NonNull final MediaStream mediaStream) {
            log.warn("Stream removed");
        }

        @Override
        public void onAddTrack(@NonNull final RtpReceiver rtpReceiver, @NonNull final MediaStream[] mediaStreams) {
            log.warn("Add track");
        }
    }

    /**
     * Handler for incoming task messages.
     */
    @AnyThread
    private class TaskMessageHandler implements MessageHandler {
        @Override
        public void onOffer(@NonNull final Offer offer) {
            log.debug("Received offer: " + offer.getSdp());
            PeerConnectionHelper.this.onOfferReceived(offer);
        }

        @Override
        public void onAnswer(@NonNull final Answer answer) {
            log.error("Unexpected answer received");
        }

        @Override
        public void onCandidates(@NonNull final Candidate[] candidates) {
            PeerConnectionHelper.this.onIceCandidatesReceived(candidates);
        }
    }

    /**
     * An offer was received. Set the remote description.
     */
    private void onOfferReceived(@NonNull final Offer offer) {
        final SessionDescription offerDescription = new SessionDescription(SessionDescription.Type.OFFER, offer.getSdp());

        // Set remote description
        this.pc.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(@NonNull final SessionDescription description) {}

            @Override
            public void onCreateFailure(@NonNull final String error) {}

            @Override
            public void onSetSuccess() {
                log.debug("Remote description set");
                PeerConnectionHelper.this.onRemoteDescriptionSet();
            }

            @Override
            public void onSetFailure(@NonNull final String s) {
                log.error("Could not set remote description: " + s);
            }
        }, offerDescription);
    }

    /**
     * The remote description was set. Create and send an answer.
     */
    private void onRemoteDescriptionSet() {
        this.pc.createAnswer(new SdpObserver() {
            @Nullable private SessionDescription answerDescription;

            @Override
            public void onCreateSuccess(@NonNull final SessionDescription description) {
                log.debug("Created answer");
                this.answerDescription = description;
                PeerConnectionHelper.this.pc.setLocalDescription(this, description);
            }

            @Override
            public void onCreateFailure(@NonNull final String error) {
                log.error("Could not create answer: " + error);
            }

            @Override
            public void onSetSuccess() {
                log.debug("Local description set");
                final Answer answer = new Answer(Objects.requireNonNull(this.answerDescription).description);
                try {
                    PeerConnectionHelper.this.task.sendAnswer(answer);
                    log.debug("Sent answer: " + answer.getSdp());
                } catch (final ConnectionException error) {
                    log.error("Could not send answer: " + error.getMessage());
                }
            }

            @Override
            public void onSetFailure(@NonNull final String error) {
                log.error("Could not set local description: " + error);
            }
        }, this.constraints);
    }

    /**
     * One or more ICE candidates were received. Store them.
     */
    private void onIceCandidatesReceived(@NonNull final Candidate[] candidates) {
        for (@Nullable final Candidate candidate : candidates) {
            if (candidate == null) {
                // Note: Unsure how to signal end-of-candidates to webrtc.org
                continue;
            }
            final IceCandidate iceCandidate = new IceCandidate(
                candidate.getSdpMid(), candidate.getSdpMLineIndex(), candidate.getSdp());
            log.debug("New remote candidate: " + candidate.getSdp());
            this.pc.addIceCandidate(iceCandidate);
        }
    }

    /**
     * Create data channel for handover and initiate handover once open.
     */
    private void prepareHandover() {
        // Get transport link
        final SignalingTransportLink link = this.task.getTransportLink();

        // Create data channel
        final DataChannel.Init parameters = new DataChannel.Init();
        parameters.id = link.getId();
        parameters.negotiated = true;
        parameters.ordered = true;
        parameters.protocol = link.getProtocol();
        final DataChannel dc = this.pc.createDataChannel(link.getLabel(), parameters);

        // Wrap as unbounded, flow-controlled data channel
        final UnboundedFlowControlledDataChannel ufcdc = new UnboundedFlowControlledDataChannel(dc);

        // Create transport handler
        final SignalingTransportHandler handler = new SignalingTransportHandler() {
            @Override
            public long getMaxMessageSize() {
                // Sigh... still not supported by webrtc.org, so fallback to a
                // well-known (and, frankly, terribly small) value.
                return 64 * 1024;
            }

            @Override
            public void close() {
                log.debug("Data channel " + dc.label() + " close request");
                dc.close();
            }

            @Override
            public void send(@NonNull final ByteBuffer message) {
                log.debug("Data channel " + dc.label() + " outgoing signaling message of length " + message.remaining());
                ufcdc.write(new DataChannel.Buffer(message, true));
            }
        };

        // Bind events
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {
                ufcdc.bufferedAmountChange();
            }

            @Override
            public void onStateChange() {
                switch (dc.state()) {
                    case CONNECTING:
                        log.debug("Data channel " + dc.label() + " connecting");
                        break;
                    case OPEN:
                        if (PeerConnectionHelper.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " re-opened");
                        } else {
                            PeerConnectionHelper.this.dcOpened = true;
                            log.info("Data channel " + dc.label() + " open");
                            task.handover(handler);
                        }
                        break;
                    case CLOSING:
                        if (!PeerConnectionHelper.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " closing");
                        } else {
                            log.debug("Data channel " + dc.label() + " closing");
                            try {
                                link.closing();
                            } catch (UntiedException error) {
                                log.warn("Could not move into closing state", error);
                            }
                        }
                        break;
                    case CLOSED:
                        if (!PeerConnectionHelper.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " closed");
                        } else {
                            log.info("Data channel " + dc.label() + " closed");
                            try {
                                link.closed();
                            } catch (UntiedException error) {
                                // Note: We can safely ignore this because, in
                                //       our case, the signalling instance may
                                //       be closed before the channel has been
                                //       through the closing sequence.
                            }
                        }
                        dc.dispose();
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {
                if (!buffer.binary) {
                    task.close(CloseCode.PROTOCOL_ERROR);
                } else {
                    try {
                        link.receive(buffer.data);
                    } catch (UntiedException error) {
                        log.warn("Could not feed incoming data to the transport link", error);
                    }
                }
            }
        });
    }

    /**
     * Close and dispose this connection.
     *
     * It cannot be reused afterwards.
     */
    public void close() {
        this.pc.dispose();
        this.factory.dispose();
    }
}
