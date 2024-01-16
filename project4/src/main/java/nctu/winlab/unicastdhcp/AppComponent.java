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
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
// import org.onosproject.net.Host;
import org.onosproject.net.Path;
// import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
// import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
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
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    private ApplicationId appId;

    private final DhcpConfigListener cfgListener = new DhcpConfigListener();
    private final ConfigFactory<ApplicationId, DhcpConfig> factory = new ConfigFactory<ApplicationId, DhcpConfig>(
            APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };
    private DhcpPacketProcessor processor = new DhcpPacketProcessor();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(factory);
        packetService.addProcessor(processor, 2);
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        intentService.getIntentsByAppId(appId).forEach((intent) -> intentService.withdraw(intent));
        packetService.removeProcessor(processor);
        processor = null;
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(factory);
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        log.info("Reconfigured");
    }

    private void requestIntercepts() {
        packetService.requestPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchIPSrc(Ip4Address.ZERO.toIpPrefix())
                .matchIPDst(Ip4Address.valueOf("255.255.255.255").toIpPrefix())
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT)).build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        packetService.cancelPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchIPSrc(Ip4Address.ZERO.toIpPrefix())
                .matchIPDst(Ip4Address.valueOf("255.255.255.255").toIpPrefix())
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT)).build(), PacketPriority.REACTIVE, appId);
    }

    private class DhcpPacketProcessor implements PacketProcessor {

        private void packetOut(PacketContext context, PortNumber port) {
            context.treatmentBuilder().setOutput(port);
            context.send();
        }

        private void flood(PacketContext context) {
            packetOut(context, PortNumber.FLOOD);
        }

        private boolean isIntentInstalling() {
            for (Intent intent : intentService.getPending()) {
                if (intent.appId() == appId) {
                    return true;
                }
            }
            for (Intent intent : intentService.getIntentsByAppId(appId)) {
                IntentState state = intentService.getIntentState(intent.key());
                if (state == IntentState.INSTALL_REQ || state == IntentState.COMPILING
                        || state == IntentState.INSTALLING) {
                    return true;
                }
            }
            return false;
        }

        private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort) {
            for (Path path : paths) {
                if (!path.src().port().equals(notToPort)) {
                    return path;
                }
            }
            return null;
        }

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            // ignore ARP packets in this application
            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                return;
            }

            DhcpConfig config = cfgService.getConfig(appId, DhcpConfig.class);

            ConnectPoint location = config.getServerLocation();
            // if intent is not ready, switch to forwarding mode
            if (isIntentInstalling()) {
                Topology currentTopology = topologyService.currentTopology();
                // check direction and get path
                Set<Path> paths = ethPkt.getDestinationMAC() == MacAddress.BROADCAST
                        ? topologyService.getPaths(currentTopology,
                                pkt.receivedFrom().deviceId(),
                                location.deviceId())
                        : topologyService.getPaths(currentTopology,
                                location.deviceId(),
                                pkt.receivedFrom().deviceId());
                if (paths.isEmpty()) {
                    // If there are no paths, flood and bail.
                    flood(context);
                    return;
                }

                // Otherwise, pick a path that does not lead back to where we
                // came from; if no such path, flood and bail.
                Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());
                if (path == null) {
                    flood(context);
                    return;
                }

                packetOut(context, path.src().port());
                return;
            }

            Key keyToServer = Key.of(pkt.receivedFrom().toString() + " " + location.toString(),
                    appId);
            Key keyToClient = Key.of(location + pkt.receivedFrom().toString(),
                    appId);
            PointToPointIntent intentToServer = PointToPointIntent.builder().appId(appId)
                    .key(keyToServer)
                    .filteredIngressPoint(new FilteredConnectPoint(pkt.receivedFrom()))
                    .filteredEgressPoint(new FilteredConnectPoint(location))
                    .selector(DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_UDP)
                            .matchEthSrc(ethPkt.getSourceMAC())
                            .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                            .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT)).build())
                    .treatment(DefaultTrafficTreatment.emptyTreatment())
                    .build();
            PointToPointIntent intentToClient = PointToPointIntent.builder().appId(appId)
                    .key(keyToClient)
                    .filteredIngressPoint(new FilteredConnectPoint(location))
                    .filteredEgressPoint(new FilteredConnectPoint(pkt.receivedFrom()))
                    .selector(DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_UDP)
                            .matchEthDst(ethPkt.getSourceMAC())
                            .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                            .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT)).build())
                    .treatment(DefaultTrafficTreatment.emptyTreatment())
                    .build();

            intentService.submit(intentToServer);
            log.info("Intent `" + pkt.receivedFrom().deviceId() + "`, port `" + pkt.receivedFrom().port() + "` => `"
                    + location.deviceId() + "`, port `" + location.port() + "` is submitted.");
            intentService.submit(intentToClient);
            log.info("Intent `" + location.deviceId() + "`, port `" + location.port() + "` => `"
                    + pkt.receivedFrom().deviceId() + "`, port `" + pkt.receivedFrom().port() + "` is submitted.");

            // forward packet
            Topology currentTopology = topologyService.currentTopology();
            // check direction and get path
            Set<Path> paths = topologyService.getPaths(currentTopology,
                    pkt.receivedFrom().deviceId(),
                    location.deviceId());
            if (paths.isEmpty()) {
                // If there are no paths, flood and bail.
                flood(context);
                return;
            }

            // Otherwise, pick a path that does not lead back to where we
            // came from; if no such path, flood and bail.
            Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());
            if (path == null) {
                flood(context);
                return;
            }

            packetOut(context, path.src().port());
        }

    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = cfgService.getConfig(appId, DhcpConfig.class);
                ConnectPoint location = config.getServerLocation();
                if (config != null) {
                    log.info("DHCP server is connected to `" + location.deviceId() + "`, port `"
                            + location.port() + "`");
                }
            }
        }
    }

}
