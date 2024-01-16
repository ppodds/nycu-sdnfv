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
package nycu.sdnfv.vrouter;

import static org.onosproject.net.config.NetworkConfigEvent.Type;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.SinglePointToMultiPointIntent;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.routeservice.ResolvedRoute;
import org.onosproject.routeservice.RouteService;
import org.onosproject.routeservice.RouteTableId;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
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

import java.util.Dictionary;
import java.util.HashSet;
import java.util.Optional;
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

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    private IpV4PacketProcessor processor = new IpV4PacketProcessor();

    private ApplicationId appId;

    private final ConfigFactory<ApplicationId, VRouterConfig> factory = new ConfigFactory<ApplicationId, VRouterConfig>(
            APP_SUBJECT_FACTORY, VRouterConfig.class, "router") {
        @Override
        public VRouterConfig createConfig() {
            return new VRouterConfig();
        }
    };
    private final NetworkConfigListener cfgListener = new VRouterConfigListener();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.sdnfv.vrouter");
        cfgService.registerConfigFactory(factory);
        cfgService.addListener(cfgListener);
        packetService.addProcessor(processor, PacketProcessor.director(6));
        requestIntercepts();
        if (cfgService.getConfig(appId, VRouterConfig.class) != null) {
            log.info("Config already existed, install intent");
            for (Intent intent : intentService.getIntentsByAppId(appId)) {
                intentService.purge(intent);
            }
            installIntent();
        }
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(factory);
        uninstallIntent();
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        log.info("Reconfigured");
    }

    private void requestIntercepts() {
        packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);
    }

    private void installIntent() {
        installBgpIntent();
        installTransitTrafficIntent();
    }

    private void installBgpIntent() {
        VRouterConfig config = cfgService.getConfig(appId, VRouterConfig.class);
        ConnectPoint quaggaConnectPoint = config.getQuagga();
        Host quagga = (Host) hostService.getConnectedHosts(quaggaConnectPoint).toArray()[0];
        // get peer connection points
        Set<FilteredConnectPoint> peerConnectionPoints = new HashSet<>();
        for (Ip4Address externalRouterAddr : config.getPeers()) {
            Interface i = interfaceService.getMatchingInterface(externalRouterAddr);
            peerConnectionPoints.add(new FilteredConnectPoint(i.connectPoint()));
        }
        for (Ip4Address externalRouterAddr : config.getPeers()) {
            intentService.submit(
                    SinglePointToMultiPointIntent.builder().appId(appId)
                            .key(Key.of("FROM_QUAGGA_INTENT_KEY_" + externalRouterAddr, appId))
                            .filteredIngressPoint(new FilteredConnectPoint(quaggaConnectPoint))
                            .filteredEgressPoints(peerConnectionPoints)
                            .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                    .matchIPDst(externalRouterAddr.toIpPrefix()).build())
                            .treatment(DefaultTrafficTreatment.emptyTreatment()).build());
        }
        for (IpAddress quaggaAddr : quagga.ipAddresses()) {
            intentService.submit(
                    MultiPointToSinglePointIntent.builder().appId(appId)
                            .key(Key.of("TO_QUAGGA_INTENT_KEY_" + quaggaAddr, appId))
                            .filteredIngressPoints(peerConnectionPoints)
                            .filteredEgressPoint(new FilteredConnectPoint(config.getQuagga()))
                            .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                    .matchIPDst(quaggaAddr.toIpPrefix()).build())
                            .treatment(DefaultTrafficTreatment.emptyTreatment()).build());
        }
    }

    private void installTransitTrafficIntent() {
        VRouterConfig config = cfgService.getConfig(appId, VRouterConfig.class);
        for (RouteTableId routeTableId : routeService.getRouteTables()) {

            for (ResolvedRoute route : routeService.getResolvedRoutes(routeTableId)) {
                Set<FilteredConnectPoint> peerConnectionPoints = new HashSet<>();
                Interface nextHopInterface = interfaceService.getMatchingInterface(route.nextHop());
                for (Ip4Address externalRouterAddr : config.getPeers()) {
                    Interface ingressInterface = interfaceService.getMatchingInterface(externalRouterAddr);
                    if (!nextHopInterface.equals(ingressInterface)) {
                        peerConnectionPoints.add(new FilteredConnectPoint(ingressInterface.connectPoint()));
                    }
                }
                intentService.submit(
                        MultiPointToSinglePointIntent.builder().appId(appId)
                                .key(Key.of("TRANSIT_INTENT_KEY_" + route.nextHop(), appId))
                                .filteredIngressPoints(peerConnectionPoints)
                                .filteredEgressPoint(new FilteredConnectPoint(nextHopInterface.connectPoint()))
                                .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                        .matchIPDst(route.prefix())
                                        .build())
                                .treatment(DefaultTrafficTreatment.builder().setEthSrc(config.getQuaggaMac())
                                        .setEthDst(route.nextHopMac()).build())
                                .build());
            }
        }
        // for (Interface i : routeService.getAllResolvedRoutes() {
        // // get peer connection points
        // Set<FilteredConnectPoint> peerConnectionPoints = new HashSet<>();
        // InterfaceIpAddress outAddr = (InterfaceIpAddress)
        // i.ipAddressesList().toArray()[0];
        // Optional<ResolvedRoute> route =
        // routeService.longestPrefixLookup(outAddr.ipAddress());
        // if (route.isEmpty()) {
        // throw new Error("Can't resolve route for addr: " + outAddr.ipAddress());
        // }
        // Ip4Address toExternalRouterAddr = null;
        // for (Ip4Address externalRouterAddr : config.getPeers()) {
        // Interface i2 = interfaceService.getMatchingInterface(externalRouterAddr);
        // if (!i.equals(i2)) {
        // peerConnectionPoints.add(new FilteredConnectPoint(i2.connectPoint()));
        // } else {
        // toExternalRouterAddr = externalRouterAddr;
        // }
        // }
        // log.info(peerConnectionPoints.toString());
        // log.info(outAddr.toString());
        // log.info(outAddr.subnetAddress().toString());
        // Set<Host> hosts = hostService.getHostsByIp(toExternalRouterAddr);
        // if (hosts.size() == 0) {
        // throw new Error("Host service can't find external router");
        // }
        // Host externalRouter = (Host) hosts.toArray()[0];
        // intentService.submit(
        // MultiPointToSinglePointIntent.builder().appId(appId)
        // .key(Key.of("TRANSIT_INTENT_KEY_" + outAddr.ipAddress(), appId))
        // .filteredIngressPoints(peerConnectionPoints)
        // .filteredEgressPoint(new FilteredConnectPoint(i.connectPoint()))
        // .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
        // .matchIPDst(route.get().prefix())
        // .build())
        // .treatment(DefaultTrafficTreatment.builder().setEthSrc(config.getQuaggaMac())
        // .setEthDst(externalRouter.mac()).build())
        // .build());
        // }
    }

    private void uninstallIntent() {
        for (Intent intent : intentService.getIntentsByAppId(appId)) {
            intentService.withdraw(intent);
        }
    }

    private class IpV4PacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            VRouterConfig config = cfgService.getConfig(appId, VRouterConfig.class);
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt.getEtherType() != Ethernet.TYPE_IPV4) {
                return;
            }

            IPv4 ipPkt = (IPv4) ethPkt.getPayload();
            Ip4Address sourceAddress = Ip4Address.valueOf(ipPkt.getSourceAddress());
            Ip4Address destAddress = Ip4Address.valueOf(ipPkt.getDestinationAddress());

            log.info("Traffic. src ip: " + sourceAddress + " src mac: " + ethPkt.getSourceMAC() + " dest ip: "
                    + destAddress + " dest mac: " + ethPkt.getDestinationMAC());
            Optional<ResolvedRoute> incomingRoute = routeService.longestPrefixLookup(sourceAddress);
            Optional<ResolvedRoute> outgoingRoute = routeService.longestPrefixLookup(destAddress);
            if (!( // inbound cross AS
            (ethPkt.getDestinationMAC().equals(config.getQuaggaMac()) && incomingRoute.isPresent())
                    // outbound cross AS
                    || (ethPkt.getDestinationMAC().equals(config.getVirtualMac()) && outgoingRoute.isPresent()))) {
                log.info("return because it is not a cross AS packet");
                // return if it is not a cross AS packet
                return;
            }

            // check the traffic source
            Key key = Key.of("vrouter_" + sourceAddress.toString() + "_" + destAddress.toString(), appId);
            if (intentService.getIntent(key) != null) {
                return;
            }

            if (ethPkt.getDestinationMAC().equals(config.getQuaggaMac()) && incomingRoute.isPresent()) {
                log.info("Inbound traffic. src: " + sourceAddress + " dest: " + destAddress);
                Set<Host> hosts = hostService.getHostsByIp(destAddress);
                if (hosts.size() == 0) {
                    log.info("Can't find the inbound traffic dest host");
                    return;
                }
                Host destHost = (Host) hosts.toArray()[0];
                // inbound traffic
                // perform L2 masquerade
                ethPkt.setSourceMACAddress(config.getVirtualMac()).setDestinationMACAddress(destHost.mac());
                intentService.submit(PointToPointIntent.builder()
                        .key(key)
                        .filteredIngressPoint(new FilteredConnectPoint(pkt.receivedFrom()))
                        .filteredEgressPoint(new FilteredConnectPoint(destHost.location()))
                        .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                .matchIPDst(destAddress.toIpPrefix()).build())
                        .treatment(DefaultTrafficTreatment.builder().setEthSrc(config.getVirtualMac())
                                .setEthDst(destHost.mac()).build())
                        .appId(appId)
                        .build());
            } else {
                // outbound traffic
                // check route
                if (outgoingRoute.isEmpty()) {
                    log.info("Can't find the outgoing traffic route. src: " + sourceAddress + " dest: " + destAddress);
                    // drop
                    return;
                }
                ResolvedRoute resolvedRoute = outgoingRoute.get();
                // perform L2 masquerade
                ethPkt.setSourceMACAddress(config.getQuaggaMac()).setDestinationMACAddress(resolvedRoute.nextHopMac());
                intentService.submit(PointToPointIntent.builder()
                        .key(key)
                        .filteredIngressPoint(new FilteredConnectPoint(pkt.receivedFrom()))
                        .filteredEgressPoint(new FilteredConnectPoint(
                                interfaceService.getMatchingInterface(resolvedRoute.nextHop()).connectPoint()))
                        .selector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                .matchIPDst(destAddress.toIpPrefix()).build())
                        .treatment(DefaultTrafficTreatment.builder().setEthSrc(config.getQuaggaMac())
                                .setEthDst(resolvedRoute.nextHopMac()).build())
                        .appId(appId)
                        .build());
            }
            context.block();
        }
    }

    private class VRouterConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == Type.CONFIG_REGISTERED
                    || event.type() == Type.CONFIG_ADDED
                    || event.type() == Type.CONFIG_UPDATED)
                    && event.configClass().equals(VRouterConfig.class)) {
                VRouterConfig config = cfgService.getConfig(appId, VRouterConfig.class);
                log.info("Config change detected");
                if (config != null) {
                    log.info("Reinstall intent because of config changing");
                    installIntent();
                }
            }
        }
    }

}
