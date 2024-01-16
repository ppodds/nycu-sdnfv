/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.ProxyArp;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyVertex;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.device.DeviceService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Set;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true, service = { AppComponent.class }, property = {
})
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    private ArpPacketProcessor processor = new ArpPacketProcessor();

    private HashMap<IpAddress, MacAddress> arpTable = new HashMap<>();

    private ApplicationId appId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        packetService.addProcessor(processor, 2);
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        log.info("Reconfigured");
    }

    private void requestIntercepts() {
        packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);
    }

    private class ArpPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            ARP arpPkt = (ARP) ethPkt.getPayload();
            IpAddress sourceIpAddress = IpAddress.valueOf(IpAddress.Version.valueOf("INET"),
                    arpPkt.getSenderProtocolAddress());
            IpAddress targetAddress = IpAddress.valueOf(IpAddress.Version.valueOf("INET"),
                    arpPkt.getTargetProtocolAddress());

            // ARP table learning
            if (!arpTable.containsKey(sourceIpAddress)) {
                arpTable.put(sourceIpAddress, ethPkt.getSourceMAC());
            }

            // if it is an ARP request packet, answer an ARP reply packet
            if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
                // if target address already in ARP table, reply directly
                if (arpTable.containsKey(targetAddress)) {
                    MacAddress targetMacAddress = arpTable.get(targetAddress);
                    log.info("TABLE HIT. Requested MAC = " + targetMacAddress);
                    Ethernet arpReply = ARP.buildArpReply(targetAddress.getIp4Address(), targetMacAddress, ethPkt);
                    OutboundPacket outboundPacket = new DefaultOutboundPacket(pkt.receivedFrom().deviceId(),
                            DefaultTrafficTreatment.builder().setOutput(pkt.receivedFrom().port()).build(),
                            ByteBuffer.wrap(arpReply.serialize()));
                    packetService.emit(outboundPacket);
                    return;
                }
                log.info("TABLE MISS. Send request to edge ports");
                // if target address isn't in ARP table, send it to all host except the origin
                // one
                for (TopologyVertex vertex : topologyService.getGraph(topologyService.currentTopology())
                        .getVertexes()) {
                    HashMap<PortNumber, Link> outLinks = new HashMap<>();
                    for (Link link : linkService.getDeviceEgressLinks(vertex.deviceId())) {
                        outLinks.put(link.src().port(), link);
                    }
                    for (Port port : deviceService.getPorts(vertex.deviceId())) {
                        // if (!port.isEnabled() || !outLinks.containsKey(port.number())) {
                        // continue;
                        // }
                        if (!port.isEnabled()) {
                            continue;
                        }
                        if (outLinks.containsKey(port.number())) {
                            Link outLink = outLinks.get(port.number());
                            if (outLink.dst().elementId() instanceof DeviceId) {
                                continue;
                            }
                        }
                        OutboundPacket outboundPacket = new DefaultOutboundPacket(vertex.deviceId(),
                                DefaultTrafficTreatment.builder().setOutput(port.number()).build(),
                                ByteBuffer.wrap(ethPkt.serialize()));
                        packetService.emit(outboundPacket);
                    }
                }
                return;
            }

            if (arpPkt.getOpCode() == ARP.OP_REPLY) {
                MacAddress senderMacAddress = MacAddress.valueOf(arpPkt.getSenderHardwareAddress());
                MacAddress targetMacAddress = MacAddress.valueOf(arpPkt.getTargetHardwareAddress());
                log.info("RECV REPLY. Requested MAC = " + senderMacAddress);
                Set<Host> hosts = hostService.getHostsByMac(targetMacAddress);
                if (hosts.size() != 1) {
                    throw new Error("Can't find host");
                }
                Host target = (Host) hosts.toArray()[0];
                ConnectPoint location = target.location();
                OutboundPacket outboundPacket = new DefaultOutboundPacket(location.deviceId(),
                        DefaultTrafficTreatment.builder().setOutput(location.port()).build(),
                        ByteBuffer.wrap(ethPkt.serialize()));
                packetService.emit(outboundPacket);
            }
        }
    }
}
